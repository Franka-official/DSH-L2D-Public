import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, cpSync, mkdirSync, writeFileSync, readFileSync, readdirSync, symlinkSync, rmSync, realpathSync, existsSync} from 'node:fs';
import {join, dirname, resolve, basename} from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {inspectRelease, installRelease, NAME, VERSION} from '../install.mjs';

const source = resolve(dirname(fileURLToPath(import.meta.url)), '../package');
function fixture(run) {
  const home = realpathSync(mkdtempSync(join(tmpdir(), 'dsh-go-release-test-')));
  const profile = join(home, 'profiles/web'), target = join(home, 'dsh-go/plugins', NAME);
  mkdirSync(profile, {recursive: true});
  writeFileSync(join(profile, 'package.json'), JSON.stringify({name: 'existing-web', private: true,
    dependencies: {'other-plugin': '1.0.0'}, dsh: {profile: {bundles: ['other-plugin']}}}));
  writeFileSync(join(home, 'settings.yaml'), 'unchanged-model-settings-sentinel');
  writeFileSync(join(home, '.credentials.yaml'), 'unchanged-private-sentinel');
  const options = {source, env: {DSH_HOME: home}};
  let calls = 0;
  const register = () => {
    calls++;
    const file = join(profile, 'package.json'), value = JSON.parse(readFileSync(file, 'utf8'));
    value.dependencies[NAME] = 'link:' + target;
    if (!value.dsh.profile.bundles.includes(NAME)) value.dsh.profile.bundles.push(NAME);
    writeFileSync(file, JSON.stringify(value));
    mkdirSync(join(profile, 'node_modules'), {recursive: true});
    const link = join(profile, 'node_modules', NAME);
    if (!existsSync(link)) symlinkSync(target, link, process.platform === 'win32' ? 'junction' : 'dir');
    return {status: 0, unknown: false};
  };
  try {run({home, profile, target, options, register, calls: () => calls});}
  finally {
    assert.equal(readFileSync(join(home, 'settings.yaml'), 'utf8'), 'unchanged-model-settings-sentinel');
    assert.equal(readFileSync(join(home, '.credentials.yaml'), 'utf8'), 'unchanged-private-sentinel');
    assert.equal(dirname(home), realpathSync(tmpdir()));
    assert.ok(basename(home).startsWith('dsh-go-release-test-'));
    rmSync(home, {recursive: true, force: true});
  }
}

test('fresh installation registers only this package and same-version rerun is a no-op', () => fixture(f => {
  assert.equal(inspectRelease(f.options).public.action, 'INSTALL_OWN_PLUGIN');
  assert.equal(existsSync(f.target), false);
  const first = installRelease({...f.options, runDsh: f.register});
  assert.equal(first.ok, true); assert.equal(first.applied, true); assert.equal(f.calls(), 1);
  assert.equal(first.profile_registered, true); assert.equal(first.clean_release_equal, true);
  const second = installRelease({...f.options, runDsh: f.register});
  assert.equal(second.action, 'ALREADY_INSTALLED'); assert.equal(second.applied, false); assert.equal(f.calls(), 1);
  const profile = JSON.parse(readFileSync(join(f.profile, 'package.json')));
  assert.equal(profile.dependencies['other-plugin'], '1.0.0'); assert.deepEqual(profile.dsh.profile.bundles, ['other-plugin', NAME]);
}));

test('same-version cleanup removes only the active package extras and retains its backup', () => fixture(f => {
  installRelease({...f.options, runDsh: f.register});
  mkdirSync(join(f.target, 'tests')); writeFileSync(join(f.target, 'tests/old.txt'), 'history');
  const result = installRelease({...f.options, runDsh: f.register});
  assert.equal(result.ok, true); assert.equal(result.original_plugin_backup_retained, true);
  assert.equal(existsSync(join(f.target, 'tests')), false); assert.equal(f.calls(), 1);
  assert.equal(readdirSync(join(f.home, 'dsh-go/plugin-backups')).length, 1);
}));

for (const previousVersion of ['0.2.7', '0.2.13', '0.2.14', '0.2.15', '0.2.16', '0.2.17']) test(`${previousVersion} upgrades to ${VERSION}`, () => fixture(f => {
  installRelease({...f.options, runDsh: f.register});
  const file = join(f.target, 'package.json'), metadata = JSON.parse(readFileSync(file)); metadata.version = previousVersion;
  writeFileSync(file, JSON.stringify(metadata)); writeFileSync(join(f.target, 'speech.mjs'), 'old-runtime');
  if (previousVersion === '0.2.17') writeFileSync(join(f.target, 'client.js'), 'old-web-runtime');
  const plan = inspectRelease(f.options).public;
  assert.equal(plan.installed_version, previousVersion); assert.equal(plan.action, 'UPGRADE_OWN_PLUGIN');
  assert.equal(plan.profile_points_to_owned_target, true);
  const result = installRelease({...f.options, runDsh: f.register});
  assert.equal(result.ok, true); assert.equal(result.installed_version, VERSION); assert.equal(result.runtime_code_equal, true);
  assert.equal(existsSync(join(f.target, 'client.js')), false);
  assert.equal(result.original_plugin_backup_retained, true); assert.equal(f.calls(), 1);
}));

test('foreign package and root directory are rejected without registration', () => fixture(f => {
  mkdirSync(f.target, {recursive: true}); writeFileSync(join(f.target, 'package.json'), JSON.stringify({name: 'foreign-package', version: '0.2.5'}));
  assert.throws(() => installRelease({...f.options, runDsh: f.register}), {code: 'FOREIGN_TARGET_PACKAGE'});
  assert.equal(f.calls(), 0);
  assert.throws(() => inspectRelease({source, env: {DSH_HOME: resolve('/')}}), {code: 'FILESYSTEM_ROOT_IS_NOT_DSH_HOME'});
}));

test('pre-swap copy failure preserves the original runtime and does not register', () => fixture(f => {
  installRelease({...f.options, runDsh: f.register});
  writeFileSync(join(f.target, 'speech.mjs'), 'original-runtime-sentinel');
  assert.throws(() => installRelease({...f.options, runDsh: f.register, beforeSwap() {throw Error('copy-check-failure');}}), /copy-check-failure/);
  assert.equal(readFileSync(join(f.target, 'speech.mjs'), 'utf8'), 'original-runtime-sentinel');
  assert.equal(f.calls(), 1);
}));

test('unconfirmed registration preserves files and refuses blind repeated registration', () => fixture(f => {
  const result = installRelease({...f.options, runDsh() {return {status: null, unknown: true};}});
  assert.equal(result.code, 'REGISTRATION_UNCONFIRMED'); assert.equal(existsSync(join(f.target, 'package.json')), true);
  assert.throws(() => installRelease({...f.options, runDsh: f.register}), {code: 'PREVIOUS_REGISTRATION_UNCONFIRMED_INSPECT_BEFORE_RETRY'});
  assert.equal(f.calls(), 0);
  assert.equal(installRelease({...f.options, retryRegistration: true, runDsh: f.register}).ok, true);
}));


test('required call policy cannot be omitted even when a package undeclares it', () => fixture(f => {
  const incomplete = join(f.home, 'incomplete-source');
  cpSync(source, incomplete, {recursive: true});
  const file = join(incomplete, 'package.json'), metadata = JSON.parse(readFileSync(file));
  metadata.files = metadata.files.filter(name => name !== 'call-policy.mjs');
  writeFileSync(file, JSON.stringify(metadata)); rmSync(join(incomplete, 'call-policy.mjs'));
  assert.throws(() => inspectRelease({...f.options, source: incomplete}), {code: 'UNEXPECTED_RELEASE_FILES'});
  assert.equal(f.calls(), 0); assert.equal(existsSync(f.target), false);
  assert.equal(existsSync(join(source, 'call-policy.mjs')), true);
}));


test('registered upgrade rolls back runtime if the profile no longer verifies', () => fixture(f => {
  installRelease({...f.options, runDsh: f.register});
  const metadataPath = join(f.target, 'package.json');
  const metadata = JSON.parse(readFileSync(metadataPath));
  metadata.version = '0.2.13';
  writeFileSync(metadataPath, JSON.stringify(metadata));
  writeFileSync(join(f.target, 'speech.mjs'), 'old-runtime');
  assert.throws(() => installRelease({...f.options, runDsh: f.register, beforeSwap() {
    const file = join(f.profile, 'package.json');
    const profile = JSON.parse(readFileSync(file));
    profile.dsh.profile.bundles = ['other-plugin'];
    writeFileSync(file, JSON.stringify(profile));
  }}), {code: 'PROFILE_CHANGED_DURING_UPGRADE'});
  assert.equal(readFileSync(join(f.target, 'speech.mjs'), 'utf8'), 'old-runtime');
  assert.equal(JSON.parse(readFileSync(metadataPath)).version, '0.2.13');
  assert.equal(f.calls(), 1);
}));
