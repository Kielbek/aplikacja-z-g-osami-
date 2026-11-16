/** Modele zgodne z API backendu Spring Boot (/api). */

export type EngineMode = 'voxcpm2' | 'piper' | 'demo' | 'offline';

export interface EngineCapabilities {
  voice_design: boolean;
  cloning: boolean;
  style_prompt: boolean;
  emotion: boolean;
  speed: boolean;
  languages: string[];
}

export interface EngineStatus {
  url: string;
  online: boolean;
  mode: EngineMode;
  engine?: string;
  model?: string | null;
  device?: string | null;
  sampleRate?: number;
  capabilities?: EngineCapabilities;
  message?: string | null;
}

export type SegmentStatus = 'empty' | 'draft' | 'ready' | 'stale' | 'error';
export type ProjectStatus = 'empty' | 'draft' | 'partial' | 'ready' | 'error';

export interface VoiceSettings {
  engine: string;
  language: string;
  voiceId: string | null;
  style: string | null;
  emotion: string | null;
  speed: number;
  expressiveness: number;
  cfgValue: number;
  inferenceTimesteps: number;
  normalize: boolean;
  denoise: boolean;
  seed: number | null;
  /** VoxCPM2 „ultimate cloning” — wyłączone: barwa z nagrania, natywny akcent języka docelowego. */
  ultimateCloning?: boolean;
}

export interface SegmentDto {
  id: string;
  position: number;
  text: string;
  status: SegmentStatus;
  durationMs: number;
  engineMode: EngineMode | null;
  generatedAt: string | null;
  error: string | null;
  audioVersion: string | null;
  /** Głos fragmentu (null = głos projektu). */
  voiceId: string | null;
}

/** Poprzednie nagranie fragmentu. */
export interface SegmentVersion {
  id: number;
  createdAt: string;
  durationMs: number;
  voiceName: string | null;
  engineMode: EngineMode | null;
  text: string;
  current: boolean;
  matchesCurrentText: boolean;
}

export interface PronunciationEntry {
  id: string;
  projectId: string | null;
  term: string;
  replacement: string;
  caseSensitive: boolean;
}

export interface ImportedText {
  title: string;
  text: string;
  characters: number;
  segments: string[];
}

export type LoudnessTarget = 'off' | '-14' | '-16' | '-19' | '-23';

export interface ProjectStats {
  characters: number;
  segmentCount: number;
  readyCount: number;
  staleCount: number;
  draftCount: number;
  errorCount: number;
  durationMs: number;
  status: ProjectStatus;
}

export interface ProjectDto {
  id: string;
  name: string;
  createdAt: string;
  updatedAt: string;
  lastGeneratedAt: string | null;
  settings: VoiceSettings;
  voice: { id: string; name: string } | null;
  stats: ProjectStats;
  segments: SegmentDto[];
  /** Folder projektu (null = bez folderu). */
  folderId?: string | null;
}

/** Folder porządkujący projekty (jeden poziom). */
export interface ProjectFolder {
  id: string;
  name: string;
  createdAt: string;
}

export type VoiceKind = 'LIBRARY' | 'DESIGNED' | 'CLONED';
export type Gender = 'female' | 'male' | 'neutral';

export interface Voice {
  id: string;
  name: string;
  kind: VoiceKind;
  gender: Gender | null;
  age: number | null;
  languages: string[];
  categories: string[];
  traits: string[];
  description: string;
  enginePrompt: string | null;
  referenceId: string | null;
  /** Domyślna instrukcja sterująca (np. wariant „Relaks”). */
  defaultInstruction?: string | null;
  /** Głos ma nagranie wzorcowe — stała barwa we wszystkich fragmentach. */
  anchored: boolean;
  sampleText: string | null;
  sampleEngineMode: EngineMode | null;
  favorite: boolean;
  useCount: number;
  createdAt: string;
}

export interface AudioAnalysis {
  duration_sec: number;
  sample_rate: number;
  channels: number;
  peak_dbfs: number;
  rms_dbfs: number;
  clipping_ratio: number;
  silence_ratio: number;
  estimated_snr_db: number;
  issues: string[];
  suitable: boolean;
}

export interface DesignPreview {
  previewId: string;
  durationMs: number;
  engineMode: EngineMode;
}

export interface GenerationRecord {
  id: number;
  type: 'segment' | 'voice_design' | 'voice_sample' | 'export';
  projectId: string | null;
  projectName: string | null;
  segmentId: string | null;
  voiceId: string | null;
  voiceName: string | null;
  textPreview: string | null;
  characters: number;
  durationMs: number;
  engineMode: EngineMode | null;
  status: 'success' | 'error';
  error: string | null;
  createdAt: string;
}

export interface HistoryItem {
  record: GenerationRecord;
  audioAvailable: boolean;
}

export interface Account {
  fullName: string;
  email: string;
  company: string;
  role: string;
  planId: PlanId;
  billingPeriodStart: string;
  renewsOn: string;
  exportFormat: 'mp3' | 'wav';
  mp3Bitrate: number;
  pauseBetweenSegmentsMs: number;
  fileNamePattern: string;
  exportLoudness: LoudnessTarget;
  theme: 'light' | 'dark' | 'system';
  defaultLanguage: string;
  defaultVoiceId: string;
  autoSplitOnPaste: boolean;
  confirmBeforeDelete: boolean;
  playAfterGenerate: boolean;
  emailNotifications: boolean;
}

export type PlanId = 'FREE' | 'STARTER' | 'PRO' | 'BUSINESS';

/** Limity planu subskrypcji (-1 = bez limitu). Źródło: GET /api/account/plans */
export interface Plan {
  id: PlanId;
  name: string;
  monthlyCharacters: number;
  monthlyMinutes: number;
  maxProjects: number;
  maxCharactersPerProject: number;
  maxCustomVoices: number;
  voiceCloning: boolean;
  exportFormats: ('mp3' | 'wav')[];
  maxMp3Bitrate: number;
  advancedControls: boolean;
  historyRetentionDays: number;
  /** Cena w groszach: za miesiąc (rozliczenie miesięczne) i za cały rok (rozliczenie roczne). */
  monthlyPriceMinor: number;
  yearlyPriceMinor: number;
  currency: string;
}

export type BillingInterval = 'month' | 'year';

export interface Usage {
  plan: Plan;
  periodStart: string;
  /** Kiedy odnowią się miesięczne limity (co miesiąc, także przy subskrypcji rocznej). */
  limitsResetOn: string;
  renewsOn: string;
  billingInterval: BillingInterval | '';
  charactersUsed: number;
  characterLimit: number;
  /** Saldo dokupionych znaków (pakiety bez subskrypcji, nie wygasają). */
  extraCharacters: number;
  minutesUsed: number;
  minuteLimit: number;
  generations: number;
  projectsUsed: number;
  projectLimit: number;
  customVoicesUsed: number;
  customVoiceLimit: number;
}

export interface ApiError {
  status: number;
  message: string;
  /** "PLAN_LIMIT" — akcja niedostępna w bieżącym planie. */
  code?: string;
}

/** Jednorazowy pakiet znaków — do kupienia bez subskrypcji. */
export interface CreditPack {
  id: string;
  name: string;
  characters: number;
  /** Cena w groszach. */
  priceMinor: number;
  currency: string;
}

export interface BillingConfig {
  stripeEnabled: boolean;
  demoPlanSwitch: boolean;
  hasSubscription: boolean;
  subscriptionStatus: string;
  billingInterval: BillingInterval | '';
  /** Pierwsza subskrypcja: pierwszy miesiąc taniej (tylko rozliczenie miesięczne). */
  introEligible: boolean;
  introPercentOff: number;
  yearlyMonthsCharged: number;
}

export type JobStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'CANCELLED' | 'FAILED';

/** Zadanie generowania w tle (kolejka w backendzie). */
export interface GenerationJob {
  id: string;
  projectId: string;
  status: JobStatus;
  total: number;
  done: number;
  failed: number;
  currentSegmentId: string | null;
  lastError: string | null;
  lastErrorCode: string | null;
  createdAt: string;
}

export interface ConsentText {
  version: string;
  text: string;
}

/** Zapisany dowód zgody na klonowanie głosu. */
export interface VoiceConsent {
  version: string;
  text: string;
  createdAt: string;
  ip: string | null;
  audioSha256: string;
  fileName: string | null;
}
