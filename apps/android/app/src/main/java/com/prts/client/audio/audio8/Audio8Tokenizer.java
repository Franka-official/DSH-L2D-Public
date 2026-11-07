package com.prts.client.audio.audio8;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact encoder for the shipped Audio8 BPE tokenizer and its reference prompt. */
public final class Audio8Tokenizer {
    public static final class Prompt {
        public final long[] values;
        public final int positions;
        public final int reusablePrefixPositions;
        Prompt(long[] v, int p, int r) { values=v; positions=p; reusablePrefixPositions=r; }
    }
    private static final class Added {
        final long id;
        final boolean singleWord, left, right;
        Added(JSONObject o) {
            id=o.optLong("id"); singleWord=o.optBoolean("single_word");
            left=o.optBoolean("lstrip"); right=o.optBoolean("rstrip");
        }
    }
    private static final class Node {
        final Map<Character,Node> next=new HashMap<>();
        Added added;
    }
    private final Map<String,Long> vocab=new HashMap<>();
    private final Map<String,Integer> ranks=new HashMap<>();
    private final Map<String,long[]> cache=new HashMap<>();
    private final Node addedRoot=new Node();
    private final char[] byteChars=new char[256];
    private final Pattern split;

    public Audio8Tokenizer(String path) throws Exception {
        JSONObject json=new JSONObject(new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8));
        if (!json.isNull("normalizer") || !json.isNull("post_processor"))
            throw new IOException("This encoder requires the unmodified Audio8 tokenizer");
        JSONObject model=json.getJSONObject("model");
        if (!model.getString("type").equals("BPE") || model.optBoolean("byte_fallback") || model.optBoolean("ignore_merges"))
            throw new IOException("Unsupported BPE configuration");
        JSONObject words=model.getJSONObject("vocab");
        for (Iterator<String> i=words.keys();i.hasNext();) {
            String k=i.next(); vocab.put(k,words.getLong(k));
        }
        JSONArray merges=model.getJSONArray("merges");
        for (int i=0;i<merges.length();i++) {
            Object m=merges.get(i);
            String a,b;
            if (m instanceof JSONArray) {
                a=((JSONArray)m).getString(0); b=((JSONArray)m).getString(1);
            } else {
                String s=(String)m; int at=s.indexOf(' ');
                if (at<0) throw new IOException("Bad BPE merge");
                a=s.substring(0,at); b=s.substring(at+1);
            }
            ranks.put(a+'\u0000'+b,i);
        }
        JSONArray tokens=json.getJSONArray("added_tokens");
        for(int i=0;i<tokens.length();i++) {
            JSONObject o=tokens.getJSONObject(i); String s=o.getString("content");
            Node n=addedRoot;
            for(int j=0;j<s.length();j++) n=n.next.computeIfAbsent(s.charAt(j), k->new Node());
            n.added=new Added(o);
        }
        JSONArray sequence=json.getJSONObject("pre_tokenizer").getJSONArray("pretokenizers");
        if(sequence.length()!=4 || !sequence.getJSONObject(0).getString("type").equals("Punctuation")
                || !sequence.getJSONObject(0).getString("behavior").equals("Contiguous")
                || sequence.getJSONObject(2).optBoolean("add_prefix_space")
                || sequence.getJSONObject(2).optBoolean("use_regex")) throw new IOException("Changed pretokenizer");
        split=Pattern.compile(sequence.getJSONObject(1).getJSONObject("pattern").getString("Regex"));
        boolean[] kept=new boolean[256];
        for(int b=33;b<=126;b++) kept[b]=true;
        for(int b=161;b<=172;b++) kept[b]=true;
        for(int b=174;b<=255;b++) kept[b]=true;
        int extra=0;
        for(int b=0;b<256;b++) byteChars[b]=(char)(kept[b]?b:256+extra++);
    }

    public long[] encode(String text) {
        List<Long> out=new ArrayList<>();
        int plain=0, at=0;
        while(at<text.length()) {
            Node n=addedRoot; Added best=null; int end=at;
            for(int j=at;j<text.length();j++) {
                n=n.next.get(text.charAt(j)); if(n==null) break;
                if(n.added!=null && (!n.added.singleWord ||
                   ((at==0 || !word(text.codePointBefore(at))) &&
                    (j+1==text.length() || !word(text.codePointAt(j+1)))))) {
                    best=n.added; end=j+1;
                }
            }
            if(best==null) { at+=Character.charCount(text.codePointAt(at)); continue; }
            int start=at;
            if(best.left) while(start>plain && whitespace(text.codePointBefore(start)))
                start-=Character.charCount(text.codePointBefore(start));
            encodePlain(text.substring(plain,start),out);
            out.add(best.id);
            if(best.right) while(end<text.length() && whitespace(text.codePointAt(end)))
                end+=Character.charCount(text.codePointAt(end));
            plain=end; at=end;
        }
        encodePlain(text.substring(plain),out);
        long[] ids=new long[out.size()];
        for(int i=0;i<ids.length;i++) ids[i]=out.get(i);
        return ids;
    }

    private static boolean word(int c) {
        int t=Character.getType(c);
        return Character.isLetterOrDigit(c) || t==Character.NON_SPACING_MARK || t==Character.COMBINING_SPACING_MARK
                || t==Character.CONNECTOR_PUNCTUATION || c==0x200c || c==0x200d;
    }
    private static boolean punctuation(int c) {
        if(c<128 && ((c>=33&&c<=47)||(c>=58&&c<=64)||(c>=91&&c<=96)||(c>=123&&c<=126))) return true;
        int t=Character.getType(c);
        return t==Character.CONNECTOR_PUNCTUATION || t==Character.DASH_PUNCTUATION || t==Character.START_PUNCTUATION
                || t==Character.END_PUNCTUATION || t==Character.INITIAL_QUOTE_PUNCTUATION
                || t==Character.FINAL_QUOTE_PUNCTUATION || t==Character.OTHER_PUNCTUATION;
    }
    private void encodePlain(String text,List<Long> out) {
        int start=0;
        while(start<text.length()) {
            boolean punct=punctuation(text.codePointAt(start)); int end=start;
            while(end<text.length() && punctuation(text.codePointAt(end))==punct)
                end+=Character.charCount(text.codePointAt(end));
            String run=text.substring(start,end); Matcher m=split.matcher(run); int prev=0;
            while(m.find()) {
                if(m.start()>prev) byteLevel(run.substring(prev,m.start()),out);
                if(m.end()>m.start()) byteLevel(m.group(),out);
                prev=m.end();
            }
            if(prev<run.length()) byteLevel(run.substring(prev),out);
            start=end;
        }
    }
    private void byteLevel(String s,List<Long> out) {
        byte[] bytes=s.getBytes(StandardCharsets.UTF_8); StringBuilder b=new StringBuilder(bytes.length);
        for(byte v:bytes) b.append(byteChars[v&255]);
        String value=b.toString(); int start=0;
        for(int i=0;i<value.length();i++) if(value.charAt(i)>='0'&&value.charAt(i)<='9') {
            if(i>start) appendBpe(value.substring(start,i),out);
            appendBpe(value.substring(i,i+1),out); start=i+1;
        }
        if(start<value.length()) appendBpe(value.substring(start),out);
    }
    private void appendBpe(String s,List<Long> out) {
        if(s.isEmpty()) return;
        long[] known=cache.get(s);
        if(known!=null) { for(long n:known) out.add(n); return; }
        List<String> pieces=new ArrayList<>();
        for(int i=0;i<s.length();i++) pieces.add(s.substring(i,i+1));
        while(pieces.size()>1) {
            int bestRank=Integer.MAX_VALUE, best=-1;
            for(int i=0;i+1<pieces.size();i++) {
                Integer rank=ranks.get(pieces.get(i)+'\u0000'+pieces.get(i+1));
                if(rank!=null && rank<bestRank) { bestRank=rank; best=i; }
            }
            if(best<0) break;
            pieces.set(best,pieces.get(best)+pieces.remove(best+1));
        }
        long[] result=new long[pieces.size()];
        for(int i=0;i<result.length;i++) {
            Long id=vocab.get(pieces.get(i));
            if(id==null) throw new IllegalArgumentException("Unknown byte token");
            result[i]=id; out.add(id);
        }
        if(cache.size()<4096) cache.put(s,result);
    }
    private static boolean whitespace(int c) {
        return Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85;
    }
    private static boolean cjk(int c) {
        return (c>=0x1100&&c<=0x11ff)||(c>=0x2e80&&c<=0x2fdf)||(c>=0x3000&&c<=0x303f)
                ||(c>=0x3040&&c<=0x30ff)||(c>=0x3100&&c<=0x31ff)||(c>=0x3400&&c<=0x4dbf)
                ||(c>=0x4e00&&c<=0x9fff)||(c>=0xa960&&c<=0xa97f)||(c>=0xac00&&c<=0xd7a3)
                ||(c>=0xd7b0&&c<=0xd7ff)||(c>=0xf900&&c<=0xfaff)||(c>=0xfe30&&c<=0xfe4f)
                ||(c>=0xff01&&c<=0xff9f)||(c>=0x20000&&c<=0x2fa1f);
    }
    private static boolean linebreak(int c) {
        return c==10||c==13||c==11||c==12||(c>=0x1c&&c<=0x1e)||c==0x85||c==0x2028||c==0x2029;
    }
    public static String cleanText(String text) {
        StringBuilder filtered=new StringBuilder();
        text.codePoints().forEach(c->{
            int t=Character.getType(c);
            if(whitespace(c) || !(t==Character.CONTROL || t==Character.FORMAT || t==Character.PRIVATE_USE
                    || t==Character.SURROGATE || t==Character.UNASSIGNED)) filtered.appendCodePoint(c);
        });
        String s=filtered.toString(); StringBuilder result=new StringBuilder();
        for(int i=0;i<s.length();) {
            int c=s.codePointAt(i);
            if(!whitespace(c)) { result.appendCodePoint(c); i+=Character.charCount(c); continue; }
            int start=i; boolean newline=false;
            while(i<s.length()&&whitespace(s.codePointAt(i))) {
                int w=s.codePointAt(i); newline|=linebreak(w); i+=Character.charCount(w);
            }
            if(!(newline&&start>0&&i<s.length()&&cjk(s.codePointBefore(start))&&cjk(s.codePointAt(i)))) result.append(' ');
        }
        return result.toString().trim();
    }
    private void add(List<Long> list,String s) { for(long id:encode(s)) list.add(id); }
    /** Immutable reference prefix only. No target text or generation suffix is accepted. */
    public Prompt buildReferencePrefix(String refText,long[][] codes) {
        if(codes.length!=10 || codes[0].length==0) throw new IllegalArgumentException("Reference needs10 codebooks");
        int frames=codes[0].length;
        for(long[] row:codes) { if(row.length!=frames) throw new IllegalArgumentException("Ragged reference");
            for(long n:row) if(n<0) throw new IllegalArgumentException("Negative reference code"); }
        refText=cleanText(refText);
        if(!Pattern.compile("<\\|speaker:\\d+\\|>").matcher(refText).find()) refText="<|speaker:0|>"+refText;
        List<Long> row0=new ArrayList<>();
        add(row0,"<|im_start|>system\n");
        add(row0,"convert the provided text to speech reference to the following:\n\nText:\n");
        add(row0,refText); add(row0,"\n\nSpeech:\n");
        int referenceStart=row0.size();
        for(long n:codes[0]) row0.add(n+65537);
        add(row0,"<|im_end|>\n"); add(row0,"<|im_start|>user\n");
        int n=row0.size(); long[] values=new long[11*n];
        for(int i=0;i<n;i++) values[i]=row0.get(i);
        for(int book=0;book<10;book++) System.arraycopy(codes[book],0,values,(book+1)*n+referenceStart,frames);
        return new Prompt(values,n,n);
    }
    public Prompt buildPrompt(String target,String refText,long[][] codes) {
        if(codes.length!=10 || codes[0].length==0) throw new IllegalArgumentException("Reference needs10 codebooks");
        int frames=codes[0].length;
        for(long[] row:codes) { if(row.length!=frames) throw new IllegalArgumentException("Ragged reference");
            for(long n:row) if(n<0) throw new IllegalArgumentException("Negative reference code"); }
        target=cleanText(target); refText=cleanText(refText);
        if(target.isEmpty()) throw new IllegalArgumentException("Empty target");
        if(!Pattern.compile("<\\|speaker:\\d+\\|>").matcher(refText).find()) refText="<|speaker:0|>"+refText;
        List<Long> row0=new ArrayList<>();
        add(row0,"<|im_start|>system\n");
        add(row0,"convert the provided text to speech reference to the following:\n\nText:\n");
        add(row0,refText); add(row0,"\n\nSpeech:\n");
        int referenceStart=row0.size();
        for(long n:codes[0]) row0.add(n+65537);
        add(row0,"<|im_end|>\n"); add(row0,"<|im_start|>user\n");
        int reusable=row0.size();
        add(row0,target); add(row0,"<|im_end|>\n"); add(row0,"<|im_start|>assistant\n<|voice|>");
        int n=row0.size(); long[] values=new long[11*n];
        for(int i=0;i<n;i++) values[i]=row0.get(i);
        for(int book=0;book<10;book++) System.arraycopy(codes[book],0,values,(book+1)*n+referenceStart,frames);
        return new Prompt(values,n,reusable);
    }
}
