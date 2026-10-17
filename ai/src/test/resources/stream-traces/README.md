# Stream trace fixtures

كل دليل يحتوي على ملفين:

- `events.jsonl`:كل سطر عنصر مسلسل `SseEvent`،مسجّل في HTTP اكتمال العميل SSE بعد تقسيم الإطارات،Provider
  قبل فك الترميز.لا تُسجل Authorization رؤوس الطلب مثل.
- `expected.json`:`StreamChunkDecoder` و `StreamChunkHandler` لقطة دلالية ثابتة بعد إتمام المعالجة.
  طابع زمني،رسالة عشوائية ID حقول متغيرة لا تدخل اللقطة مثل.

عند إنشاء أو تحديث المسار،يجب الاحتفاظ Provider المُرجَع `id`،`event` و `data`،مراجعة يدوية قبل أول إرسال
`expected.json`.اختبارات الوحدة العادية تعيد تشغيل هذه الملفات في وضع عدم الاتصال فقط،لا يصل إلى الشبكة.

بعد تسجيل مسار جديد،متاح في `ai` في دليل الوحدة شغّل الأمر التالي لإعادة إنشاء لقطة دلالية:

```bash
UPDATE_STREAM_TRACE_SNAPSHOTS=true ../gradlew testDebugUnitTest \
  --tests me.rerere.ai.provider.stream.StreamTraceReplayTest
```

ستحتفظ اللقطة باستدعاءات الأدوات ID،كامل metadata،نص للتفكير،اسم الأداة والمعاملات و token usage.تُحتفظ بيانات الصورة في
`events.jsonl`،تسجل اللقطة فقط MIME النوع،مجموع البايتات بعد فك الترميز SHA-256،تجنّب تخزين مقاطع كبيرة مكررة Base64.
Gemini مسار الصورة سيقارن حرفيًا أيضًا Provider التوقيع الأصلي وما بعد فك الترميز metadata،التحقق من التوقيع أثناء التحويل المتدفق
لم يتم تعديله.
