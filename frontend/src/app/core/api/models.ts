/** Modele zgodne z API backendu Spring Boot (/api). */

export type EngineMode = 'voxcpm2' | 'demo' | 'offline';

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
}

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
  plan: string;
  monthlyCharacterLimit: number;
  monthlyMinuteLimit: number;
  billingPeriodStart: string;
  renewsOn: string;
  exportFormat: 'mp3' | 'wav';
  mp3Bitrate: number;
  pauseBetweenSegmentsMs: number;
  fileNamePattern: string;
  theme: 'light' | 'dark' | 'system';
  defaultLanguage: string;
  defaultVoiceId: string;
  autoSplitOnPaste: boolean;
  confirmBeforeDelete: boolean;
  playAfterGenerate: boolean;
  emailNotifications: boolean;
}

export interface Usage {
  plan: string;
  periodStart: string;
  renewsOn: string;
  charactersUsed: number;
  characterLimit: number;
  minutesUsed: number;
  minuteLimit: number;
  generations: number;
}

export interface ApiError {
  status: number;
  message: string;
}
