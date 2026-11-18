/** 三种创作模式的纯规则；Web 与 Android 壳共用，不依赖 React。 */
export type CreationMode = 'manual' | 'assisted' | 'autonomous';

export const CREATION_MODES: readonly CreationMode[] = ['manual', 'assisted', 'autonomous'];

export const MODE_LABEL: Record<CreationMode, string> = {
  manual: '传统写作',
  assisted: 'AI 辅助',
  autonomous: '纯 AI',
};

export const MODE_DESC: Record<CreationMode, string> = {
  manual: '你写每一个字，AI 不介入正文。',
  assisted: 'AI 给候选段落，你决定是否采纳。',
  autonomous: '你给方向，多 Agent 工作流产出候选章节。',
};

/** 传统模式不进入 AI 流程；辅助/纯 AI 共用后端 workflow。 */
export function usesAi(mode: CreationMode): boolean {
  return mode !== 'manual';
}

/** 在编辑器中插入 AI 候选时，传统模式自动升级为辅助模式。 */
export function upgradeModeForInsert(mode: CreationMode): CreationMode {
  return mode === 'manual' ? 'assisted' : mode;
}

export function isCreationMode(value: unknown): value is CreationMode {
  return typeof value === 'string' && CREATION_MODES.includes(value as CreationMode);
}

export function normalizeCreationMode(value: unknown): CreationMode {
  return isCreationMode(value) ? value : 'manual';
}
