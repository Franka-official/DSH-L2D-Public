import { SoullinkRuntime, type RuntimeSnapshot } from '../upstream/soullink/packages/engine/src/runtime/SoullinkRuntime';
import { motionStylePresets } from '../upstream/soullink/packages/engine/src/runtime/MotionStyle';
import { SpeechPerformancePlanner, deriveSpeechPerformanceCapabilities, type SpeechPerformancePlan } from '../upstream/soullink/packages/engine/src/speech/SpeechPerformancePlanner';
import { validateModelProfile } from '../upstream/soullink/packages/engine/src/profile/ModelProfileSchema';
import type { ModelProfile, ParameterMapRule } from '../upstream/soullink/packages/engine/src/profile/ModelProfile';
import type { VADVector } from '../upstream/soullink/packages/engine/src/emotion/VADState';
import type { VADPrivateParameterInfo } from '../upstream/soullink/packages/engine/src/emotion/VADPrivateParameterOverlay';

// The adapter only routes events/ownership. Motion, FACS, VAD, speech planning,
// profile mapping and smoothing remain the actual pinned upstream components.
const REVISION = '02cd75ac0a085a87d84d1a885f1b33eca8742d53';
const ACTIVE = ['SoullinkRuntime', 'EmotionStateController', 'VADExpressionMapper',
  'IdleEngine', 'VADMicroMotionController', 'ReactionSequencer', 'MotionMixer',
  'ActionUnitSolver', 'ModelProfileAdapter', 'LayeredParameterMixer'];
const FACE_KEYS = new Set(['browInnerUp', 'browOuterUp', 'browDown', 'eyeOpen', 'eyeSmile',
  'eyeSquint', 'eyeBlinkL', 'eyeBlinkR', 'gazeX', 'gazeY', 'mouthSmile', 'mouthFrown', 'mouthPucker', 'blush', 'tear', 'sweat']);
const ARM_IDS = new Set(['Movement1', 'Movement2', 'Movement3', 'Param35', 'Param38',
  'Param40', 'Param62', 'Param59', 'Param60', 'Param61', 'Param36', 'Param39', 'Param41',
  'Param42', 'Param43', 'Param44', 'Param25', 'LArm', 'LForearm', 'LWrist', 'LHand',
  'LHand2', 'RArm', 'RForearm', 'RWrist', 'RHand', 'RHand2']);
const EMOTIONS = new Set(['neutral', 'calm', 'happy', 'excited', 'shy', 'affectionate',
  'curious', 'confused', 'tired', 'sad', 'anxiety', 'anger', 'concerned', 'surprised', 'fearful', 'disgusted']);
type Message = Record<string, any>;
let runtime: SoullinkRuntime | null = null;
let profile: ModelProfile | null = null;
let planner = new SpeechPerformancePlanner();
let seed = 1, eventOrdinal = 0, frameCount = 0, speechPlans = 0, emotionEvents = 0;
let hostOrigin: number | null = null, lastT = 0, mouthLevel = 0, speaking = false;
let manualFace = false, armBusy = false;
let speechEndAt = 0, speechDurationKnown = false, lastPlan: SpeechPerformancePlan | null = null;
let emotion = 'neutral', intensity = 0, confidence = 1;
let allowed = new Set<string>(), faceIds = new Set<string>(), mouthIds = new Set<string>();
let metadata: Record<string, VADPrivateParameterInfo> = {};
let privateSummary: unknown = null;
let profileWarnings: string[] = [];

const finite = (value: unknown, fallback: number): number =>
  typeof value === 'number' && Number.isFinite(value) ? value : fallback;
const bounded = (value: unknown, fallback: number, low = 0, high = 1): number =>
  Math.max(low, Math.min(high, finite(value, fallback)));
const targets = (rule?: ParameterMapRule): string[] =>
  rule ? [...(rule.target ? [rule.target] : []), ...(rule.targets ?? [])] : [];
const reserved = (id: string): boolean => mouthIds.has(id) || ARM_IDS.has(id)
  || /(?:mouth.*open|jaw.*open|arm|forearm|wrist|elbow|shoulder|hand)/i.test(id);
function clock(message: Message): number {
  if (typeof message.nowMs === 'number' && Number.isFinite(message.nowMs)) {
    hostOrigin ??= message.nowMs;
    lastT = Math.max(lastT, (message.nowMs - hostOrigin) / 1000);
  }
  return lastT;
}
function resolveEmotion(raw: unknown): string {
  const name = typeof raw === 'string' ? raw.trim().toLowerCase() : 'neutral';
  const normalized = name === 'thinking' ? 'confused' : name === 'angry' ? 'anger' : name;
  return EMOTIONS.has(normalized) ? normalized : 'neutral';
}
function readVAD(message: Message): Partial<VADVector> | undefined {
  const result: Partial<VADVector> = {};
  const source = message.vad && typeof message.vad === 'object' ? message.vad : message;
  for (const key of ['valence', 'arousal', 'dominance'] as const) {
    if (typeof source[key] === 'number' && Number.isFinite(source[key])) result[key] = bounded(source[key], 0, -1, 1);
  }
  return Object.keys(result).length ? result : undefined;
}
function resetAdapter() {
  runtime = null; profile = null; hostOrigin = null; lastT = 0; mouthLevel = 0;
  eventOrdinal = 0; frameCount = 0; speechPlans = 0; emotionEvents = 0;
  speaking = false; speechEndAt = 0; speechDurationKnown = false; lastPlan = null;
  manualFace = false; armBusy = false;
  emotion = 'neutral'; intensity = 0; confidence = 1;
  planner = new SpeechPerformancePlanner(); allowed = new Set(); faceIds = new Set(); mouthIds = new Set();
  metadata = {}; privateSummary = null; profileWarnings = [];
}
function initialize(message: Message) {
  resetAdapter();
  const validated = validateModelProfile(message.profile);
  if (!validated.ok || !validated.profile) throw new Error('invalid_profile: ' + validated.errors.join('; '));
  profile = validated.profile;
  profileWarnings = validated.warnings;
  seed = (Math.abs(Math.trunc(finite(message.seed, 1))) >>> 0) || 1;
  for (const [key, rule] of Object.entries(profile.parameterMap)) {
    for (const id of targets(rule)) {
      allowed.add(id);
      if (FACE_KEYS.has(key)) faceIds.add(id);
      if (key === 'mouthOpen') mouthIds.add(id);
    }
  }
  for (const rule of Object.values(profile.customParams ?? {})) for (const id of targets(rule)) allowed.add(id);
  for (const mapping of Object.values(profile.privateEmotionMap ?? {})) {
    for (const id of [...(mapping.target ? [mapping.target] : []), ...(mapping.targets ?? [])]) {
      allowed.add(id); faceIds.add(id);
    }
  }
  // Real Core ranges are supplied by the host; no guessed private parameter limits.
  if (Array.isArray(message.parameterMetadata)) {
    for (const row of message.parameterMetadata) {
      if (!row || typeof row.id !== 'string' || !allowed.has(row.id) || reserved(row.id)) continue;
      if (![row.min, row.max, row.default].every(x => typeof x === 'number' && Number.isFinite(x))) continue;
      if (row.min > row.max || row.default < row.min || row.default > row.max) continue;
      metadata[row.id] = {min:row.min, max:row.max, default:row.default,
        ...(typeof row.name === 'string' ? {name:row.name} : {})};
    }
  }
  runtime = new SoullinkRuntime({profile,
    motionStyle:{...motionStylePresets.natural, seed, spontaneity:0, idleActionGain:0, gestureFrequency:0},
    audioLevelAnalyzer:{getLevel:()=>mouthLevel, isAvailable:()=>true}});
  // The profile holds character-specific calibration; do not multiply SDK defaults again.
  runtime.setParameterGain(1); runtime.setBodyMotionGain(1);
  runtime.setProactiveRepeatEnabled(false); runtime.clearVoiceWaitingMotion();
  privateSummary = runtime.setPrivateVADParameters(metadata);
  runtime.reset(0);
  return snapshot(runtime.update(clock(message), 0), message);
}
function planSpeech(t: number, durationMs: number, known: boolean) {
  const active = runtime!;
  lastPlan = planner.plan({emotion, intensity, confidence,
    vad:active.getSnapshot().vad.current, durationMs,
    lifecycleToken:++eventOrdinal, turnOrdinal:speechPlans + 1,
    seed:(seed + eventOrdinal) >>> 0 || 1,
    capabilities:deriveSpeechPerformanceCapabilities(profile!)});
  active.startSpeechPerformance(lastPlan, t, 'replace');
  speechPlans++;
  speechEndAt = t + lastPlan.durationMs / 1000;
  speechDurationKnown = known;
}
function startSpeech(message: Message, t: number) {
  speaking = true; runtime!.setVoicePlaybackActive(true);
  if (typeof message.emotion === 'string') emotion = resolveEmotion(message.emotion);
  intensity = bounded(message.intensity, intensity); confidence = bounded(message.confidence, confidence);
  const known = typeof message.durationMs === 'number' && message.durationMs > 0 && Number.isFinite(message.durationMs);
  // Fallback is only for a real audio-start event without supplied duration, never a wait timer.
  planSpeech(t, bounded(message.durationMs, 2200, 400, 120000), known);
}
function endSpeech() {
  speaking = false; mouthLevel = 0; runtime!.setVoicePlaybackActive(false);
  runtime!.clearSpeechPerformance(); runtime!.clearSpeechMotion();
}
function snapshot(state: RuntimeSnapshot, message: Message) {
  const parameters: Record<string, number> = {};
  for (const [id, raw] of Object.entries(state.live2dParams)) {
    if (!allowed.has(id) || reserved(id) || (manualFace && faceIds.has(id)) || !Number.isFinite(raw)) continue;
    const range = metadata[id];
    parameters[id] = range ? Math.max(range.min, Math.min(range.max, raw)) : raw;
  }
  const nativeAnimation = !manualFace && state.nativeAnimation
    ? {...state.nativeAnimation, motion:null,
      suppressParamIds:state.nativeAnimation.suppressParamIds.filter(id=>allowed.has(id) && !reserved(id))}
    : null;
  const activeComponents = [...ACTIVE];
  if ((privateSummary as any)?.candidateCount > 0) activeComponents.push('VADPrivateParameterOverlay');
  if (speechPlans > 0) activeComponents.push('SpeechPerformancePlanner', 'sampleSpeechPerformance', 'LipSyncController');
  return {ready:true, parameters, nativeAnimation, facs:state.facs, vad:state.vad,
    diagnostics:{schema:'dsh-soullink-native-v1', sourceRevision:REVISION, modelId:profile!.modelId,
      engineState:state.state, activeComponents, inactiveComponents:['AIRI_MAGIC_untrained_not_enabled', 'VoiceWaitingMotionController', 'idleActionScheduler_discrete_actions', 'VADGestureController_discrete_actions'],
      frames:frameCount, emotionEvents, speechPlans, speechGestureCount:lastPlan?.gestures.length ?? 0,
      speechPlanDurationMs:lastPlan?.durationMs ?? 0, speechDurationKnown, voicePlaybackActive:speaking,
      speechPerformanceActive:speaking && lastT < speechEndAt, manualFace, armBusy,
      privateParameterSummary:privateSummary, parameterGain:state.parameterGain, bodyMotionGain:state.bodyMotionGain,
      discreteWaitingActions:false, proactiveEventDispatch:false, nativeArmMotionDispatch:false,
      outputMouthOpenness:false, outputArmChannels:false, metadataParameters:Object.keys(metadata).length,
      profileWarnings, hostTimeSeconds:lastT}};
}
function processMessage(message: Message) {
  if (!message || typeof message !== 'object' || Array.isArray(message)) throw new Error('message_must_be_object');
  if (message.type === 'init') return initialize(message);
  if (!runtime || !profile) throw new Error('not_initialized');
  const t = clock(message);
  if (message.type === 'emotion') {
    emotion = resolveEmotion(message.emotion); intensity = bounded(message.intensity, 0.5);
    confidence = bounded(message.confidence, 1);
    const vadTarget = readVAD(message);
    runtime.triggerIntent({emotion,intensity,contextTags:[]}, t,
      {seed:(seed + ++eventOrdinal) >>> 0 || 1, vadTarget, provider:'host_emotion'});
    emotionEvents++;
    // triggerIntent clears upstream speech performance: re-plan only its real remaining interval.
    if (speaking && speechEndAt > t) planSpeech(t, Math.max(400, (speechEndAt-t)*1000), speechDurationKnown);
  } else if (message.type === 'speech_start') startSpeech(message, t);
  else if (message.type === 'speech_end') endSpeech();
  else if (message.type === 'step') {
    if (typeof message.manualFace === 'boolean') manualFace = message.manualFace;
    if (typeof message.armBusy === 'boolean') armBusy = message.armBusy;
    mouthLevel = bounded(message.mouthLevel, 0);
    if (message.speaking === true && !speaking) startSpeech(message, t);
    else if (message.speaking === false && speaking) endSpeech();
    frameCount++;
  } else throw new Error('unsupported_message_type');
  return snapshot(runtime.update(t, message.type === 'step' ? bounded(message.dtMs,50,0,100)/1000 : 0), message);
}
(globalThis as any).DshPerformance = Object.freeze({
  handle(raw: string): string {
    const begin = typeof performance !== 'undefined' && typeof performance.now === 'function' ? performance.now() : Date.now();
    try {
      const result = processMessage(JSON.parse(raw));
      const end = typeof performance !== 'undefined' && typeof performance.now === 'function' ? performance.now() : Date.now();
      (result.diagnostics as any).jsElapsedMs = Math.max(0,end-begin);
      return JSON.stringify(result);
    } catch (error) {
      return JSON.stringify({ready:false,error:error instanceof Error ? error.message : String(error),
        diagnostics:{schema:'dsh-soullink-native-v1',sourceRevision:REVISION}});
    }
  }
});
