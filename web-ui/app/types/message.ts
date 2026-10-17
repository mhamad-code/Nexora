import type { MessageRole, TokenUsage } from "./core";
import type { UIMessageAnnotation } from "./annotations";
import type { UIMessagePart } from "./parts";

/**
 * لقطة اسم النموذج المحفوظة على الرسالة،استخدمه لعرض المصدر بعد حذف النموذج
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - ModelSnapshot
 */
export interface ModelSnapshot {
  modelId: string;
  displayName: string;
}

/**
 * UI Message
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessage
 */
export interface UIMessage {
  id: string;
  role: MessageRole;
  parts: UIMessagePart[];
  annotations: UIMessageAnnotation[];
  createdAt: string;
  finishedAt?: string | null;
  modelId?: string | null;
  modelSnapshot?: ModelSnapshot | null;
  usage?: TokenUsage | null;
  translation?: string | null;
  isContextCheckpoint?: boolean;
}
