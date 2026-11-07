#!/usr/bin/env python3
"""Derive the 36-state incremental codec from the pinned Audio8 ONNX graph."""

import copy
import math
from pathlib import Path
import tempfile

import numpy as np
import onnx
from onnx import TensorProto as T, helper as h, numpy_helper as nh


def write_checked_graph(model, destination, expected_size):
    if destination.exists():
        raise ValueError(f"Codec destination already exists: {destination}")
    with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".onnx", delete=False) as out:
        temporary = Path(out.name)
        out.close()
        try:
            onnx.save(model, str(temporary))
            onnx.checker.check_model(str(temporary), full_check=False)
            if temporary.stat().st_size != expected_size:
                raise ValueError("Incremental graph differs from the tested asset size")
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)


def build_codec(source, destination, expected_size):
    """Share the unchanged external FP16 data and add streaming state to the graph."""
    if source.parent.resolve() != destination.parent.resolve():
        raise ValueError("The codec graphs must share their external data directory")
    original = onnx.load(str(source), load_external_data=False)
    by_name={node.name:node for node in original.graph.node}
    producers={value:node for node in original.graph.node for value in node.output}
    initializers={value.name:value for value in original.graph.initializer}

    def attrs(node):return {a.name:h.get_attribute_value(a) for a in node.attribute}
    constant_cache={}
    def const(name):
        if name in constant_cache:return constant_cache[name]
        if name in initializers:
            tensor=initializers[name]
            if tensor.data_location==T.EXTERNAL or math.prod(tensor.dims)>4096:raise ValueError('Not a small constant: '+name)
            value=nh.to_array(tensor)
        else:
            node=producers[name];a=attrs(node)
            if node.op_type=='Constant':value=nh.to_array(a['value'])
            else:
                xs=[const(x) if x else None for x in node.input]
                if node.op_type=='Concat':value=np.concatenate(xs,axis=a['axis'])
                elif node.op_type=='Reshape':value=np.reshape(xs[0],xs[1].tolist())
                elif node.op_type=='Transpose':value=np.transpose(xs[0],a.get('perm'))
                elif node.op_type=='Cast':value=xs[0].astype(h.tensor_dtype_to_np_dtype(a['to']))
                elif node.op_type=='Unsqueeze':
                    value=xs[0]
                    for axis in sorted(xs[1].tolist()):value=np.expand_dims(value,axis)
                elif node.op_type=='Squeeze':value=np.squeeze(xs[0],axis=tuple(xs[1]))
                elif node.op_type=='Gather':value=np.take(xs[0],xs[1],axis=a.get('axis',0))
                elif node.op_type=='Shape':value=np.array(xs[0].shape,dtype=np.int64)
                elif node.op_type=='ConstantOfShape':value=np.full(xs[0].tolist(),nh.to_array(a['value']).reshape(-1)[0] if 'value' in a else 0)
                elif node.op_type=='Slice':
                    slices=[slice(None)]*xs[0].ndim
                    axes=xs[3] if len(xs)>3 and xs[3] is not None else np.arange(len(xs[1]))
                    steps=xs[4] if len(xs)>4 and xs[4] is not None else np.ones(len(xs[1]),dtype=np.int64)
                    for start,end,axis,step in zip(xs[1],xs[2],axes,steps):slices[int(axis)]=slice(int(start),int(end),int(step))
                    value=xs[0][tuple(slices)]
                elif node.op_type=='Add':value=xs[0]+xs[1]
                elif node.op_type=='Sub':value=xs[0]-xs[1]
                elif node.op_type=='Mul':value=xs[0]*xs[1]
                elif node.op_type=='Neg':value=-xs[0]
                elif node.op_type=='Identity':value=xs[0]
                else:raise ValueError('Unsupported constant operator '+node.op_type+' at '+node.name)
        constant_cache[name]=np.asarray(value);return constant_cache[name]

    window_sub=int(const(by_name['/post_module/Sub'].input[1]).reshape(-1)[0])
    window_add=int(const(by_name['/post_module/Add'].input[1]).reshape(-1)[0])
    window=window_sub-window_add+1
    assert window>0,(window_sub,window_add)

    def prune(model,outputs,inputs):
        need=set(x.name for x in outputs);keep=[]
        for node in reversed(model.graph.node):
            if any(x in need for x in node.output):keep.append(node);need.update(x for x in node.input if x)
        keep.reverse();del model.graph.node[:];model.graph.node.extend(keep)
        kept_init=[x for x in model.graph.initializer if x.name in need]
        del model.graph.initializer[:];model.graph.initializer.extend(kept_init)
        del model.graph.input[:];model.graph.input.extend(x for x in inputs if x.name in need)
        del model.graph.output[:];model.graph.output.extend(outputs)
        del model.graph.value_info[:]

    model=copy.deepcopy(original)
    inputs=list(copy.deepcopy(original.graph.input));outputs=[];states=[];new_nodes=[]
    inputs += [h.make_tensor_value_info('frame_offset',T.INT64,[]),h.make_tensor_value_info('attention_mask',T.BOOL,[1,1,'chunk_frames','key_frames'])]
    def initializer(name,value):
        model.graph.initializer.append(nh.from_array(np.asarray(value),name));return name
    def node(op,ins,outs,name,**kwargs):return h.make_node(op,ins,outs,name=name,**kwargs)
    for layer in range(8):
        for kind in ('k','v'):
            inp=f'past_{kind}_{layer}';out=f'next_{kind}_{layer}'
            inputs.append(h.make_tensor_value_info(inp,T.FLOAT16,['batch',8,'past_frames',64]))
            outputs.append(h.make_tensor_value_info(out,T.FLOAT16,['batch',8,'next_frames',64]))
            states.append(dict(input=inp,output=out,dtype='float16',shape=[1,8,0,64],axis=2,retain=window-1,kind='attention_'+kind))
    for old in model.graph.node:
        current=copy.deepcopy(old)
        if current.name=='/post_module/Range_1':
            output=current.output[0];current.output[0]=output+'_local';new_nodes.append(current)
            new_nodes += [node('Cast',['frame_offset'],['a8_offset_f32'],'a8/offset_cast',to=T.FLOAT),node('Add',[output+'_local','a8_offset_f32'],[output],'a8/global_rope')]
            continue
        for layer in range(8):
            prefix=f'/post_module/layers.{layer}/attention/'
            kind='k' if current.name==prefix+'Transpose_1' else 'v' if current.name==prefix+'Transpose_2' else None
            if kind:
                output=current.output[0];current.output[0]=output+'_new';new_nodes.append(current)
                new_nodes += [node('Concat',[f'past_{kind}_{layer}',output+'_new'],[output],f'a8/layer_{layer}/concat_{kind}',axis=2),
                              node('Identity',[output],[f'next_{kind}_{layer}'],f'a8/layer_{layer}/return_{kind}')]
                break
        else:
            if current.op_type=='Where' and '/attention/' in current.name:current.input[0]='attention_mask'
            new_nodes.append(current)
            continue
        # The matched transpose plus concat/identity have already been appended.
    del model.graph.node[:];model.graph.node.extend(new_nodes)
    conv_info=[];transpose_info=[];rebuilt=[]
    axis2=initializer('a8_axis2',np.array([2],dtype=np.int64))
    endmax=initializer('a8_endmax',np.array([np.iinfo(np.int64).max],dtype=np.int64))
    shape_index2=initializer('a8_shape_index2',np.array(2,dtype=np.int64))
    axes0=initializer('a8_axes0',np.array([0],dtype=np.int64))
    pad_prefix=initializer('a8_pad_prefix5',np.zeros(5,dtype=np.int64))

    for old in model.graph.node:
        current=copy.deepcopy(old)
        if current.op_type=='Conv' and attrs(current).get('kernel_shape')!=[1]:
            a=attrs(current);pad_node=producers[current.input[0]]
            assert pad_node.op_type=='Pad',current.name
            padding=const(pad_node.input[1]).astype(np.int64).tolist();mode=attrs(pad_node).get('mode',b'constant')
            assert mode in (b'constant','constant'),(current.name,mode)
            k=int(a['kernel_shape'][0]);d=int(a['dilations'][0]);tail=(k-1)*d
            assert padding==[0,0,tail,0,0,0],(current.name,padding,tail)
            if len(pad_node.input)>2 and pad_node.input[2]:assert float(const(pad_node.input[2]))==0,current.name
            weight=initializers[current.input[1]];channels=int(weight.dims[1])*int(a.get('group',1))
            index=len(conv_info);prefix=f'a8/conv_{index}';inp=f'conv_tail_{index}';out=f'next_conv_tail_{index}'
            inputs.append(h.make_tensor_value_info(inp,T.FLOAT16,['batch',channels,tail]))
            outputs.append(h.make_tensor_value_info(out,T.FLOAT16,['batch',channels,tail]))
            states.append(dict(input=inp,output=out,dtype='float16',shape=[1,channels,tail],kind='conv'))
            joined=prefix+'/joined';start=initializer(f'a8_conv_{index}_start',np.array([-tail],dtype=np.int64))
            rebuilt += [node('Concat',[inp,pad_node.input[0]],[joined],prefix+'/concat',axis=2),node('Slice',[joined,start,endmax,axis2],[out],prefix+'/save_tail')]
            current.input[0]=joined;conv_info.append(dict(node=current.name,tail=tail,channels=channels,padding=padding,mode=mode.decode() if isinstance(mode,bytes) else mode))
        elif current.op_type=='ConvTranspose':
            a=attrs(current);k=int(a['kernel_shape'][0]);stride=int(a['strides'][0]);tail=k-stride
            if tail:
                assert list(a.get('pads',[0,0]))==[0,0] and int(a.get('group',1))==1,current.name
                weight=initializers[current.input[1]];channels=int(weight.dims[1]);assert len(current.input)==3,current.name
                index=len(transpose_info);prefix=f'a8/transpose_{index}';inp=f'transpose_tail_{index}';out=f'next_transpose_tail_{index}'
                inputs.append(h.make_tensor_value_info(inp,T.FLOAT,['batch',channels,tail]))
                outputs.append(h.make_tensor_value_info(out,T.FLOAT,['batch',channels,tail]))
                states.append(dict(input=inp,output=out,dtype='float32',shape=[1,channels,tail],kind='transpose'))
                original_output=current.output[0];raw=prefix+'/raw_biased_f16';current.output[0]=raw
                rebuilt.append(current)
                bias_shape=initializer(f'a8_transpose_{index}_bias_shape',np.array([1,channels,1],dtype=np.int64))
                tail_scalar=initializer(f'a8_transpose_{index}_tail_size',np.array(tail,dtype=np.int64))
                start=initializer(f'a8_transpose_{index}_start',np.array([-tail],dtype=np.int64))
                rebuilt += [node('Cast',[raw],[prefix+'/raw_f32'],prefix+'/cast_raw',to=T.FLOAT),
                            node('Cast',[current.input[2]],[prefix+'/bias_f32'],prefix+'/cast_bias',to=T.FLOAT),
                            node('Reshape',[prefix+'/bias_f32',bias_shape],[prefix+'/bias'],prefix+'/reshape_bias'),
                            node('Sub',[prefix+'/raw_f32',prefix+'/bias'],[prefix+'/no_bias'],prefix+'/remove_bias'),
                            node('Shape',[raw],[prefix+'/shape'],prefix+'/shape_raw'),
                            node('Gather',[prefix+'/shape',shape_index2],[prefix+'/length'],prefix+'/gather_length',axis=0),
                            node('Sub',[prefix+'/length',tail_scalar],[prefix+'/pad_right'],prefix+'/tail_pad_length'),
                            node('Unsqueeze',[prefix+'/pad_right',axes0],[prefix+'/pad_right_vec'],prefix+'/tail_pad_vector'),
                            node('Concat',[pad_prefix,prefix+'/pad_right_vec'],[prefix+'/pads'],prefix+'/tail_pad_config',axis=0),
                            node('Pad',[inp,prefix+'/pads'],[prefix+'/padded_tail'],prefix+'/pad_tail',mode='constant'),
                            node('Add',[prefix+'/no_bias',prefix+'/padded_tail'],[prefix+'/merged'],prefix+'/overlap_add'),
                            node('Slice',[prefix+'/merged',start,endmax,axis2],[out],prefix+'/save_tail'),
                            node('Add',[prefix+'/merged',prefix+'/bias'],[prefix+'/biased'],prefix+'/apply_bias_once'),
                            node('Cast',[prefix+'/biased'],[original_output],prefix+'/cast_output',to=T.FLOAT16)]
                transpose_info.append(dict(node=current.name,kernel=k,stride=stride,tail=tail,channels=channels))
                continue
        rebuilt.append(current)
    del model.graph.node[:];model.graph.node.extend(rebuilt)
    prune(model,[copy.deepcopy(original.graph.output[0])]+outputs,inputs)
    write_checked_graph(model, destination, expected_size)
