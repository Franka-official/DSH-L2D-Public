/* Portable source build. npm ci && npm run build deploys the Android bundle. */
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const esbuild = require('esbuild');
const root = __dirname;
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'SOURCE-MANIFEST.json'), 'utf8'));
if (esbuild.version !== '0.25.12') throw new Error('esbuild 0.25.12 is required; run npm ci');
const args = process.argv.slice(2);
if (args.length && (args.length !== 2 || args[0] !== '--out')) throw new Error('Usage: npm run build -- [--out path]');
const output = args.length ? path.resolve(process.cwd(), args[1])
  : path.resolve(root, '../app/src/main/assets/soullink/performance.bundle.js');
const tempRoot = path.resolve(os.tmpdir());
const work = fs.mkdtempSync(path.join(tempRoot, 'dsh-l2d-motion-'));
try {
  // Stable logical source labels preserve the original esbuild comments and
  // allow byte-for-byte reproduction without depending on a host's checkout path.
  const source = path.join(work, manifest.stable_source_label_prefix);
  for (const file of manifest.source_files) {
    if (path.isAbsolute(file.path) || file.path.split(/[\\/]/).includes('..')) throw new Error('Invalid source path');
    const destination = path.join(source, file.path);
    fs.mkdirSync(path.dirname(destination), {recursive:true});
    fs.copyFileSync(path.join(root, file.path), destination);
  }
  const result = esbuild.buildSync({absWorkingDir:work, bundle:true, target:'es2020',
    legalComments:'inline', minify:false, metafile:true, logLevel:'warning',
    banner:{js:`/*! Soullink Emotion SDK ${manifest.revision}; Apache-2.0; DSH L2D adapter. See licenses. */`},
    platform:'neutral', format:'iife', entryPoints:[path.join(source,'adapter/dsh-performance.ts')],
    outfile:path.join(work,'out/performance.bundle.js'), write:false});
  const bytes = result.outputFiles[0].contents;
  const changed = !fs.existsSync(output) || !Buffer.from(bytes).equals(fs.readFileSync(output));
  if (changed) {
    fs.mkdirSync(path.dirname(output), {recursive:true});
    const temporary = output + '.motion.tmp';
    fs.writeFileSync(temporary, bytes, {flag:'wx'});
    fs.renameSync(temporary, output);
  }
  const report = {esbuild:esbuild.version, upstream_revision:manifest.revision, output,
    bytes:bytes.length, changed, source_files:manifest.source_files.length,
    profile_assets_written:false, native_or_kotlin_written:false};
  const evidence = path.join(root,'.build');
  fs.mkdirSync(evidence,{recursive:true});
  fs.writeFileSync(path.join(evidence,'performance.metafile.json'),JSON.stringify(result.metafile,null,2)+'\n');
  fs.writeFileSync(path.join(evidence,'build-report.json'),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report));
} finally {
  const resolved = path.resolve(work);
  if (!resolved.startsWith(tempRoot + path.sep) || !path.basename(resolved).startsWith('dsh-l2d-motion-')) {
    throw new Error('Unexpected temporary build directory');
  }
  fs.rmSync(resolved,{recursive:true,force:true});
}
