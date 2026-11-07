import {readFileSync, writeFileSync, lstatSync, realpathSync, existsSync, mkdirSync, readdirSync,
  mkdtempSync, copyFileSync, renameSync, rmSync} from 'node:fs';
import {homedir} from 'node:os';
import {resolve, join, dirname, basename, relative, parse, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';

export const NAME = 'dsh-go-live2d-call';
export const VERSION = '0.2.18';
const SUPPORTED_OLD = new Set(['0.1.0', '0.1.1', '0.1.2', '0.2.0', '0.2.1', '0.2.2', '0.2.3', '0.2.4', '0.2.5', '0.2.6', '0.2.7', '0.2.8', '0.2.9', '0.2.10', '0.2.11', '0.2.12', '0.2.13', '0.2.14', '0.2.15', '0.2.16', '0.2.17', VERSION]);
const RUNTIME = ['plugin.js', 'native-panel.mjs', 'adapter.mjs', 'call-policy.mjs', 'relay.mjs', 'identity.mjs', 'carrier.mjs', 'manager.mjs', 'speech.mjs', 'local-http.mjs', 'cordis.patch.yml', 'proactive.mjs'];
const fail = code => {throw Object.assign(new Error(code), {code});};
const json = path => JSON.parse(readFileSync(path, 'utf8'));
const inside = (child, parent) => {const value = relative(parent, child); return value !== '' && !value.startsWith('..' + sep) && value !== '..' && !parse(value).root;};
const stable = value => JSON.stringify(value, (_key, row) => row && !Array.isArray(row) && typeof row === 'object'
  ? Object.fromEntries(Object.keys(row).sort().map(key => [key, row[key]])) : row);

function regular(path) {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) fail('REGULAR_FILE_REQUIRED');
}
function directory(path) {
  const stat = lstatSync(path);
  if (!stat.isDirectory() || stat.isSymbolicLink()) fail('REGULAR_DIRECTORY_REQUIRED');
}
function childDirectory(home, parts, create = false) {
  let path = home;
  for (const part of parts) {
    path = join(path, part);
    if (!inside(path, home)) fail('UNSAFE_TARGET_PATH');
    if (existsSync(path)) directory(path);
    else if (create) mkdirSync(path);
  }
  return path;
}
function resolveHome(env) {
  const configured = env.DSH_HOME;
  let selected = typeof configured === 'string' && configured.trim() ? configured : join(homedir(), '.dsh');
  if (selected === '~') selected = homedir();
  else if (/^~[/\\]/.test(selected)) selected = join(homedir(), selected.slice(2));
  const home = realpathSync(resolve(selected));
  if (home === parse(home).root) fail('FILESYSTEM_ROOT_IS_NOT_DSH_HOME');
  directory(home);
  return home;
}
function unrelatedProfile(value) {
  const copy = structuredClone(value);
  copy.dependencies ||= {}; delete copy.dependencies[NAME];
  copy.dsh ||= {}; copy.dsh.profile ||= {};
  copy.dsh.profile.bundles = (copy.dsh.profile.bundles || []).filter(name => name !== NAME);
  return stable(copy);
}
function registration(profileFile, target) {
  const value = json(profileFile), bundles = value.dsh?.profile?.bundles;
  if (!Array.isArray(bundles) || !bundles.every(name => typeof name === 'string')) fail('INITIALIZE_DSH_GO_PROFILE_FIRST');
  if (bundles.filter(name => name === NAME).length > 1) fail('DUPLICATE_PLUGIN_REGISTRATION');
  let pointsToTarget = false;
  if (value.dependencies?.[NAME] !== undefined) {
    if (typeof value.dependencies[NAME] !== 'string') fail('INVALID_OWN_DEPENDENCY');
    try {
      const found = createRequire(profileFile).resolve(NAME + '/package.json');
      regular(found);
      if (json(found).name !== NAME) fail('PROFILE_NAME_OCCUPIED_BY_FOREIGN_PACKAGE');
      pointsToTarget = existsSync(target) && realpathSync(dirname(found)) === realpathSync(target);
    } catch (error) {
      if (error.code === 'PROFILE_NAME_OCCUPIED_BY_FOREIGN_PACKAGE') throw error;
    }
  }
  return {value, registered: bundles.includes(NAME) && pointsToTarget, pointsToTarget};
}
function sourcePackage(source) {
  directory(source); regular(join(source, 'package.json'));
  const pkg = json(join(source, 'package.json'));
  if (pkg.name !== NAME || pkg.version !== VERSION || !Array.isArray(pkg.files) || pkg.dependencies || pkg.peerDependencies || pkg.scripts)
    fail('UNEXPECTED_RELEASE_PACKAGE');
  const files = ['package.json', ...pkg.files];
  if (new Set(files).size !== files.length || !RUNTIME.every(file => files.includes(file))) fail('UNEXPECTED_RELEASE_FILES');
  for (const file of files) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(file) || /^(?:tests?|node_modules)$/i.test(file)) fail('UNSAFE_RELEASE_FILE');
    regular(join(source, file));
  }
  if (readdirSync(source).length !== files.length) fail('UNDECLARED_RELEASE_PAYLOAD');
  return {pkg, files};
}
function equalFiles(source, target, files) {
  return files.every(file => {try {regular(join(target, file)); return readFileSync(join(source, file)).equals(readFileSync(join(target, file)));} catch {return false;}});
}
function readReceipt(path) {
  if (!existsSync(path)) return undefined;
  regular(path);
  const value = json(path);
  if (value.package !== NAME) fail('FOREIGN_INSTALL_RECEIPT');
  return value;
}
function removeOwnStage(path, parent) {
  const resolved = resolve(path), canonicalParent = realpathSync(parent);
  if (dirname(resolved) !== canonicalParent || !basename(resolved).startsWith('.' + NAME + '-stage-')) fail('UNSAFE_STAGE_CLEANUP');
  directory(resolved);
  rmSync(resolved, {recursive: true, force: true});
}

/** Reads only package/profile metadata and this plugin's public files. */
export function inspectRelease(options = {}) {
  const env = options.env || process.env;
  const source = realpathSync(options.source || join(dirname(fileURLToPath(import.meta.url)), 'package'));
  const {files} = sourcePackage(source);
  const home = resolveHome(env);
  const profiles = childDirectory(home, ['profiles', 'web']);
  const profileFile = join(profiles, 'package.json'); regular(profileFile);
  const plugins = childDirectory(home, ['dsh-go', 'plugins']);
  const target = join(plugins, NAME);
  if (!inside(target, home) || source === target || inside(source, target) || inside(target, source)) fail('UNSAFE_SOURCE_OR_TARGET');
  let installed;
  if (existsSync(target)) {
    directory(target); regular(join(target, 'package.json')); installed = json(join(target, 'package.json'));
    if (installed.name !== NAME) fail('FOREIGN_TARGET_PACKAGE');
    if (!SUPPORTED_OLD.has(installed.version)) fail('UNSUPPORTED_TARGET_VERSION');
  }
  const stateDir = childDirectory(home, ['dsh-go', 'install-state']);
  const receiptFile = join(stateDir, NAME + '.json'), previous = readReceipt(receiptFile);
  const registered = registration(profileFile, target);
  const runtimeEqual = Boolean(installed) && equalFiles(source, target, RUNTIME);
  const cleanEqual = Boolean(installed) && readdirSync(target).length === files.length && equalFiles(source, target, files);
  const action = cleanEqual && registered.registered ? 'ALREADY_INSTALLED'
    : installed?.version === VERSION ? 'CLEAN_OR_REGISTER_SAME_VERSION' : installed ? 'UPGRADE_OWN_PLUGIN' : 'INSTALL_OWN_PLUGIN';
  return {source, files, home, plugins, target, profileFile, receiptFile, previous, registered, installed,
    public: {package: NAME, bundle_version: VERSION, installed_version: installed?.version || null,
      runtime_code_equal: runtimeEqual, clean_release_equal: cleanEqual, profile_registered: registered.registered,
      profile_points_to_owned_target: registered.pointsToTarget, loaded_runtime_observed: false,
      action, engine_reload_required: action !== 'ALREADY_INSTALLED'}};
}

function officialRegistration(context) {
  const result = spawnSync('dsh', ['plugin', '--profile', 'web', 'add', 'link:' + context.target, '--offline', '--ignore-scripts'],
    {env: {...process.env, DSH_HOME: context.home}, stdio: ['ignore', 'pipe', 'pipe'], timeout: 60000, maxBuffer: 1024 * 1024});
  return {status: result.status, unknown: Boolean(result.error || result.signal)};
}

export function installRelease(options = {}) {
  const context = inspectRelease(options);
  if (context.public.action === 'ALREADY_INSTALLED') return {...context.public, applied: false, ok: true};
  if (context.previous?.status === 'REGISTRATION_UNCONFIRMED' && !context.registered.registered && !options.retryRegistration)
    fail('PREVIOUS_REGISTRATION_UNCONFIRMED_INSPECT_BEFORE_RETRY');
  const beforeProfile = unrelatedProfile(context.registered.value);
  childDirectory(context.home, ['dsh-go', 'plugins'], true);
  const backupRoot = childDirectory(context.home, ['dsh-go', 'plugin-backups'], true);
  const stateRoot = childDirectory(context.home, ['dsh-go', 'install-state'], true);
  const writeReceipt = value => writeFileSync(join(stateRoot, NAME + '.json'), JSON.stringify({package: NAME, version: VERSION,
    ...value}, null, 2) + '\n', {mode: 0o600});
  let backup, stage;
  if (!context.public.clean_release_equal) {
    stage = mkdtempSync(join(context.plugins, '.' + NAME + '-stage-'));
    try {
      for (const file of context.files) copyFileSync(join(context.source, file), join(stage, file));
      if (!equalFiles(context.source, stage, context.files)) fail('RELEASE_COPY_MISMATCH');
      options.beforeSwap?.();
      if (context.installed) {
        backup = join(backupRoot, NAME + '-' + context.installed.version + '-' + Date.now() + '-' + randomUUID());
        if (!inside(backup, context.home) || existsSync(backup)) fail('UNSAFE_BACKUP_PATH');
        renameSync(context.target, backup);
      }
      try {renameSync(stage, context.target); stage = undefined;}
      catch (error) {if (backup) renameSync(backup, context.target); throw error;}
    } finally {if (stage && existsSync(stage)) removeOwnStage(stage, context.plugins);}
  }
  let current;
  try {
    current = registration(context.profileFile, context.target);
    if (context.registered.registered && (!current.registered || unrelatedProfile(current.value) !== beforeProfile))
      fail('PROFILE_CHANGED_DURING_UPGRADE');
  } catch (error) {
    if (backup) {
      const rejected = join(backupRoot, NAME + '-rejected-' + VERSION + '-' + randomUUID());
      renameSync(context.target, rejected);
      try {renameSync(backup, context.target);}
      catch (restoreError) {
        renameSync(rejected, context.target);
        restoreError.applied = true;
        throw restoreError;
      }
    }
    throw error;
  }
  if (!current.registered) {
    let result;
    try {
      result = (options.runDsh || officialRegistration)(context);
      current = registration(context.profileFile, context.target);
    } catch {
      writeReceipt({status: 'REGISTRATION_UNCONFIRMED', backup: backup ? relative(context.home, backup) : null});
      return {...context.public, applied: true, ok: false, code: 'REGISTRATION_UNCONFIRMED',
        original_plugin_backup_retained: Boolean(backup), engine_reload_required: true, automatic_retry: false};
    }
    if (result.unknown || result.status !== 0 || !current.registered) {
      writeReceipt({status: 'REGISTRATION_UNCONFIRMED', backup: backup ? relative(context.home, backup) : null});
      return {...context.public, applied: true, ok: false, code: 'REGISTRATION_UNCONFIRMED',
        original_plugin_backup_retained: Boolean(backup), engine_reload_required: true, automatic_retry: false};
    }
  }
  if (unrelatedProfile(current.value) !== beforeProfile) {
    writeReceipt({status: 'UNRELATED_PROFILE_CHANGE_OBSERVED', backup: backup ? relative(context.home, backup) : null});
    return {...context.public, applied: true, ok: false, code: 'UNRELATED_PROFILE_CHANGE_OBSERVED', automatic_retry: false};
  }
  writeReceipt({status: 'INSTALLED', backup: backup ? relative(context.home, backup) : null});
  return {...inspectRelease(options).public, applied: true, ok: true,
    original_plugin_backup_retained: Boolean(backup), engine_reload_required: true};
}

function main() {
  const args = process.argv.slice(2);
  if (args.some(arg => !['--plan', '--status', '--install', '--retry-registration', '--help'].includes(arg))) fail('UNKNOWN_OPTION');
  if (args.includes('--help')) {
    console.info('node install.mjs [--plan|--status|--install] [--retry-registration]'); return;
  }
  if (args.filter(arg => ['--plan', '--status', '--install'].includes(arg)).length > 1) fail('CHOOSE_ONE_ACTION');
  const result = args.includes('--install') ? installRelease({retryRegistration: args.includes('--retry-registration')})
    : {...inspectRelease().public, applied: false, mode: args.includes('--status') ? 'status' : 'plan'};
  console.info(JSON.stringify(result));
  if (result.ok === false) process.exitCode = 1;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {main();} catch (error) {
    console.info(JSON.stringify({ok: false, applied: error.applied === true, code: /^[A-Z_]{1,100}$/.test(error.code || '') ? error.code : 'INSTALLER_CHECK_FAILED'}));
    process.exitCode = 1;
  }
}
