package com.mhamad.nexora.data.datastore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.ProviderSetting
import com.mhamad.nexora.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

/**
 */
val RECOMMENDED_PROVIDERS: List<ProviderSetting> = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append("تقديم OpenAI،Claude،Google Gemini خدمة عالية التوازي وثابتة لنماذج رئيسية مثل")
                    appendLine()
                    append("الموقع الرسمي:")
                    withLink(LinkAnnotation.Url("https://aihubmix.com")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://aihubmix.com")
                        }
                    }
                    appendLine()
                    append("شحن رصيد: ")
                    withLink(LinkAnnotation.Url("https://console.aihubmix.com/topup")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://console.aihubmix.com/topup")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd"),
        name = "تأملAIالبوابة",
        baseUrl = "https://sui-xiang.com/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append("موثوق وفعال API خدمة الترحيل،تقديم Claude،Codex،Gemini خدمات وسيطة مثل.الاهتمام بالخصوصية·لا إعادة بيع للبيانات·لا تلاعب بالنماذج،مبلغ الشحن 1:1،الدفع حسب الاستخدام.تعدد خطوط الارتباط المزعوم،استرداد الكوارث عبر المناطق،تبديل تلقائي عند العطل،ارتباط طويل المدى SSE من دون انقطاع.")
                    appendLine()
                    append("الموقع الرسمي:")
                    withLink(LinkAnnotation.Url("https://sui-xiang.com")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://sui-xiang.com")
                        }
                    }
                }
            )
        },
    ),
)
