import type { UIMessage } from "./message";

/**
 * Message node - container for message branching
 * @see app/src/main/java/com/mhamad/nexora/data/model/Conversation.kt - MessageNode
 */
export interface MessageNode {
  id: string;
  messages: UIMessage[];
  selectIndex: number;
}

/**
 * الإعدادات المثبتة على الجلسة بعد البدء؛لا توجد هذه الإعدادات قبل بدء الجلسة،تبديل منطقة إدخال الدردشة يغيّر المساعد مباشرة
 * @see app/src/main/java/com/mhamad/nexora/data/model/ConversationConfig.kt - ConversationConfig
 */
export interface ConversationConfig {
  chatModelId?: string | null;
  reasoningLevel?: string | null;
  enableWebSearch?: boolean;
  /** بحث مدمج في النموذج،مفتاح تجاوز إعدادات النموذج الافتراضية */
  builtInSearch?: boolean;
  mcpServers?: string[];
  workspaceId?: string | null;
  enabledSkills?: string[];
}

/**
 * Conversation
 * @see app/src/main/java/com/mhamad/nexora/data/model/Conversation.kt - Conversation
 */
export interface Conversation {
  id: string;
  assistantId: string;
  title: string;
  messageNodes: MessageNode[];
  chatSuggestions: string[];
  isPinned: boolean;
  customSystemPrompt?: string | null;
  modeInjectionIds?: string[];
  lorebookIds?: string[];
  config?: ConversationConfig | null;
  /** Absolute path inside the workspace rootfs */
  workspaceCwd?: string | null;
  /** المجلد التابع(تجميع داخل المساعد)،null يُشير إلى عدم الانتماء لأي مجلد */
  folderId?: string | null;
  createAt: number;
  updateAt: number;
}
