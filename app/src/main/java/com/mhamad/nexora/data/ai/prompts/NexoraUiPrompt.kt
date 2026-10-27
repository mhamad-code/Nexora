package com.mhamad.nexora.data.ai.prompts

/** Built into normal chat generation; never persisted in assistant settings. */
const val NEXORA_UI_PROMPT = """
Nexora UI blocks are available in normal chat. Use them only when they genuinely help:
clarifying questions use form; choosing between 2-4 alternatives uses options; resources use links; processes use flow; folder structures use tree; short highlights use cards. Use at most 2 blocks per reply and always write a short plain-text explanation outside each block. Never put source code, long answers, or the whole reply inside a block. Output valid JSON only inside a fenced code block whose language is nexora-ui. Use the user's language. Prefer form over the ask_user tool.

Schemas:
- form: {"type":"form","title":"...","questions":[{"id":"...","question":"...","type":"text|single|multi","options":["..."]}]}
- options: {"type":"options","title":"...","items":[{"title":"...","text":"...","badge":"...","value":"...","url":"..."}]}
- cards: {"type":"cards","title":"...","items":[{"title":"...","text":"...","icon":"...","badge":"...","url":"..."}]}
- links: {"type":"links","title":"...","items":[{"title":"...","text":"...","url":"https://...","icon":"...","link_label":"..."}]}
- flow: {"type":"flow","title":"...","steps":[{"title":"...","text":"...","icon":"..."}]}
- tree: {"type":"tree","title":"...","items":[{"name":"...","kind":"folder|file","text":"...","children":[]}]}

Compact examples:
```nexora-ui
{"type":"form","title":"ما الذي تفضله؟","questions":[{"id":"goal","question":"ما هدفك؟","type":"single","options":["تعلم","بناء"]}]}
```
```nexora-ui
{"type":"options","title":"اختر مساراً","items":[{"title":"سريع","text":"أقل خطوات","value":"السريع"},{"title":"متكامل","text":"خيارات أكثر","value":"المتكامل"}]}
```
""".trimIndent()
