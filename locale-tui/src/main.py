#!/usr/bin/env python3
"""Android Locale Manager TUI Application."""

import re
import sys
import asyncio
from typing import Callable
from pathlib import Path

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent))

import click
from config import Config
from app import LocaleTuiApp
from services.xml_parser import StringsXmlParser
from services.translator import AITranslator
from models.entry import TranslationEntry


def load_config() -> Config:
    """Load configuration from file."""
    config_path = Path(__file__).parent.parent / "config.yml"

    if not config_path.exists():
        click.echo(f"خطأ:ملف التكوين غير موجود {config_path}", err=True)
        click.echo("يرجى الإنشاء استنادًا إلى القالب config.yml ملف.", err=True)
        sys.exit(1)

    try:
        config = Config.load(config_path)
    except Exception as e:
        click.echo(f"خطأ:فشل تحميل التكوين - {e}", err=True)
        sys.exit(1)

    # Validate configuration
    if not config.openai_api_key:
        click.echo("تحذير:غير مُعد OPENAI_API_KEY.AI ميزة الترجمة لن تكون متاحة.", err=True)

    return config


@click.group(invoke_without_command=True)
@click.pass_context
def cli(ctx):
    """Android Locale Manager - إدارة وترجمة Android مصدر السلسلة

    تشغيل بدون معلمات TUI واجهة،استخدم الأوامر الفرعية للعمل من سطر الأوامر.
    """
    if ctx.invoked_subcommand is None:
        # No command provided, launch TUI
        config = load_config()
        app = LocaleTuiApp(config)
        app.run()


@cli.command("test-connection")
def test_connection():
    """اختبار AI اتصال الخدمة

    \b
    مثال:
        locale-tui test-connection
    """
    config = load_config()

    if not config.openai_api_key:
        click.echo("خطأ:غير مُعد OPENAI_API_KEY،تعذر اختبار الاتصال.", err=True)
        sys.exit(1)

    click.echo("AI تكوين الخدمة:")
    click.echo(f"  Base URL: {config.openai_base_url}")
    click.echo(f"  Model: {config.translation_model}")
    click.echo("جارٍ اختبار الاتصال...")

    async def test_async():
        translator = AITranslator(config)
        return await translator.test_connection()

    try:
        content = asyncio.run(test_async())
        click.echo("✓ تم الاتصال بنجاح")
        if content:
            click.echo(f"استجابة: {content}")
        else:
            click.echo("الاستجابة فارغة،لكن API تم إرجاع نتيجة صالحة.")
    except Exception as e:
        click.echo(f"✗ فشل الاتصال: {e}", err=True)
        sys.exit(1)


@cli.command()
@click.argument("key")
@click.argument("value")
@click.option(
    "--module",
    "-m",
    default=None,
    help="اسم الوحدة(يستخدم افتراضيًا الوحدة الأولى في ملف التعريف)",
)
@click.option("--skip-translate", is_flag=True, help="تخطي الترجمة التلقائية،أضف فقط مدخلات اللغة المصدر")
def add(key: str, value: str, module: str, skip_translate: bool):
    """إضافة مدخل لغوي جديد وترجمته تلقائياً

    \b
    مثال:
        locale-tui add hello_world "Hello, World!"
        locale-tui add greeting "Welcome" -m app
        locale-tui add test_key "Test" --skip-translate
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"خطأ:الموديل غير موجود '{module}'", err=True)
            click.echo(f"وحدات متاحة:{', '.join(m.name for m in config.modules)}", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("خطأ:الوحدة غير معرّفة في ملف التكوين", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    click.echo(f"استخدام الوحدة: {selected_module.name}")

    # Get source language
    source_lang = config.get_source_language()
    if not source_lang:
        click.echo("خطأ:لم يتم تكوين لغة المصدر", err=True)
        sys.exit(1)

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    if not res_dir.exists():
        click.echo(f"خطأ:دليل الموارد غير موجود {res_dir}", err=True)
        sys.exit(1)

    # Add entry to source language file
    source_file = res_dir / "values" / "strings.xml"
    click.echo(f"أضف مدخلاً إلى {source_file.relative_to(config.project_root)}...")

    try:
        StringsXmlParser.update_entry(source_file, key, value)
        click.echo(f"✓ تمت إضافة مدخل: {key} = {value}")
    except Exception as e:
        click.echo(f"خطأ:فشل إضافة المدخل - {e}", err=True)
        sys.exit(1)

    # Translate to other languages
    if not skip_translate:
        target_languages = [lang.code for lang in config.languages if not lang.is_source]

        if not target_languages:
            click.echo("لم يتم تكوين لغة الهدف،تخطي الترجمة.")
            return

        click.echo(f"ابدأ الترجمة إلى {len(target_languages)} لغات...")

        # Create entry for translation
        entry = TranslationEntry(key=key, translations={"values": value})

        async def translate_async():
            translator = AITranslator(config)

            async def translate_one(lang_code: str):
                lang_name = config.get_language_name(lang_code)

                try:
                    translations = await translator.translate_batch(
                        {key: value}, lang_name
                    )

                    if key in translations:
                        return lang_code, lang_name, translations[key], None
                    return lang_code, lang_name, None, "فشل الترجمة(لم تُرجع نتيجة)"
                except Exception as e:
                    return lang_code, lang_name, None, str(e)

            tasks = [translate_one(lang_code) for lang_code in target_languages]
            results = await asyncio.gather(*tasks)

            for lang_code, lang_name, translated_value, error in results:
                click.echo(f"ترجم إلى {lang_name}...", nl=False)

                if error:
                    click.echo(f" ✗ خطأ: {error}", err=True)
                    continue

                entry.set_translation(lang_code, translated_value)

                # Save to file
                target_file = res_dir / lang_code / "strings.xml"
                StringsXmlParser.update_entry(target_file, key, translated_value)

                click.echo(f" ✓ {translated_value}")

        asyncio.run(translate_async())
        click.echo("مكتمل!")


@cli.command()
@click.argument("key")
@click.argument("value")
@click.option(
    "--lang",
    "-l",
    default=None,
    help="رمز اللغة(مثال:values, values-zh, values-ja)،الافتراضي هو لغة المصدر",
)
@click.option(
    "--module",
    "-m",
    default=None,
    help="اسم الوحدة(يستخدم افتراضيًا الوحدة الأولى في ملف التعريف)",
)
def set(key: str, value: str, lang: str, module: str):
    """تعيين يدوي لقيمة عنصر بلغة محددة

    \b
    مثال:
        locale-tui set hello_world "مرحبًا،العالم!" -l values-zh
        locale-tui set greeting "Welcome" -l values
        locale-tui set test_key "テスト" -l values-ja -m app
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"خطأ:الموديل غير موجود '{module}'", err=True)
            click.echo(f"وحدات متاحة:{', '.join(m.name for m in config.modules)}", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("خطأ:الوحدة غير معرّفة في ملف التكوين", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    # Resolve language directory
    if lang is None:
        lang = "values"  # Default to source language

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    if not res_dir.exists():
        click.echo(f"خطأ:دليل الموارد غير موجود {res_dir}", err=True)
        sys.exit(1)

    # Target file
    target_file = res_dir / lang / "strings.xml"
    lang_name = config.get_language_name(lang) if lang != "values" else "لغة المصدر"

    click.echo(f"إعداد {lang_name} مدخلات الـ: {key} = {value}")
    click.echo(f"الملف الهدف: {target_file.relative_to(config.project_root)}")

    try:
        StringsXmlParser.update_entry(target_file, key, value)
        click.echo(f"✓ تم الإعداد بنجاح")
    except Exception as e:
        click.echo(f"خطأ:فشل الإعداد - {e}", err=True)
        sys.exit(1)


@cli.command()
@click.option(
    "--module",
    "-m",
    default=None,
    help="اسم الوحدة(يستخدم افتراضيًا الوحدة الأولى في ملف التعريف)",
)
def list_keys(module: str):
    """سرد مفاتيح جميع مدخلات اللغة

    \b
    مثال:
        locale-tui list-keys
        locale-tui list-keys -m app
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"خطأ:الموديل غير موجود '{module}'", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("خطأ:الوحدة غير معرّفة في ملف التكوين", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    source_file = res_dir / "values" / "strings.xml"

    if not source_file.exists():
        click.echo(f"خطأ:ملف المصدر غير موجود {source_file}", err=True)
        sys.exit(1)

    # Parse and display
    entries = StringsXmlParser.parse(source_file)

    click.echo(f"وحدة '{selected_module.name}' مشترك {len(entries)} مدخل:")
    click.echo()

    for key in sorted(entries.keys()):
        value = entries[key]
        # Truncate long values
        if len(value) > 60:
            value = value[:57] + "..."
        click.echo(f"  {key:40} {value}")


_batch_options = [
    click.option(
        "--lang",
        "-l",
        "langs",
        multiple=True,
        help="رمز لغة الهدف،قابل للتكرار(مثال:-l values-ar -l values-ja)،تحديد كل اللغات غير المصدرية افتراضيًا",
    ),
    click.option(
        "--module",
        "-m",
        "module_names",
        multiple=True,
        help="اسم الوحدة،قابل للتكرار،تحديد كل الوحدات افتراضيًا",
    ),
    click.option("--concurrency", "-c", default=8, show_default=True, help="عدد الطلبات المتزامنة"),
    click.option("--retries", default=3, show_default=True, help="أقصى محاولات لكل دفعة"),
    click.option("--dry-run", is_flag=True, help="احتساب عناصر للترجمة فقط،لا تستدعي الترجمة"),
]


def batch_options(func):
    for option in reversed(_batch_options):
        func = option(func)
    return func


def run_batch_translation(
    langs: tuple[str, ...],
    module_names: tuple[str, ...],
    concurrency: int,
    retries: int,
    dry_run: bool,
    select: Callable[[dict[str, str], dict[str, str]], dict[str, str]],
    action: str,
):
    """Translate entries picked by select(source, existing) for each module/language.

    Results are validated by AITranslator.translate_entries and written in place.
    """
    config = load_config()

    if not dry_run and not config.openai_api_key:
        click.echo("خطأ:غير مُعد OPENAI_API_KEY،تعذر الترجمة.", err=True)
        sys.exit(1)

    known_langs = [lang.code for lang in config.languages if not lang.is_source]
    for code in langs:
        if code not in known_langs:
            click.echo(f"خطأ:لم يتم العثور على اللغة الهدف في التكوين '{code}'", err=True)
            click.echo(f"لغات متاحة:{', '.join(known_langs)}", err=True)
            sys.exit(1)
    target_langs = list(langs) or known_langs

    modules = config.modules
    if module_names:
        modules = [m for m in config.modules if m.name in module_names]
        unknown = {*module_names} - {m.name for m in modules}
        if unknown:
            click.echo(f"خطأ:الموديل غير موجود {', '.join(sorted(unknown))}", err=True)
            click.echo(f"وحدات متاحة:{', '.join(m.name for m in config.modules)}", err=True)
            sys.exit(1)

    all_failures: list[tuple[str, str, str, str]] = []

    async def run_all():
        translator = None if dry_run else AITranslator(config)
        for module in modules:
            res_dir = config.project_root / module.res_path
            source_file = res_dir / "values" / "strings.xml"
            if not source_file.exists():
                continue
            source = StringsXmlParser.parse(source_file)

            for lang_code in target_langs:
                lang_name = config.get_language_name(lang_code)
                target_file = res_dir / lang_code / "strings.xml"
                existing = StringsXmlParser.parse(target_file)
                selected = select(source, existing)
                if not selected:
                    continue

                label = f"[{module.name}/{lang_code}]"
                click.echo(f"{label} {action} {len(selected)} عنصر")
                if dry_run:
                    continue

                def on_progress(done: int, total: int, label=label):
                    click.echo(f"\r{label} {done}/{total}", nl=False)

                translations, failures = await translator.translate_entries(
                    selected,
                    lang_name,
                    concurrency=concurrency,
                    retries=retries,
                    progress_callback=on_progress,
                )
                click.echo()

                if translations:
                    StringsXmlParser.update_entries(target_file, translations)
                click.echo(
                    f"{label} ✓ كتابة {len(translations)}/{len(selected)} عنصر -> "
                    f"{target_file.relative_to(config.project_root)}"
                )
                for key, error in failures.items():
                    all_failures.append((module.name, lang_code, key, error))

    asyncio.run(run_all())

    if all_failures:
        click.echo(f"\n{len(all_failures)} فشل ترجمة عنصر(لم يُكتب،قابل لإعادة التشغيل):", err=True)
        for module_name, lang_code, key, error in all_failures:
            click.echo(f"  {module_name}/{lang_code} {key}: {error}", err=True)
        sys.exit(1)

    click.echo("مكتمل!")


@cli.command("translate-missing")
@batch_options
def translate_missing(**options):
    """ترجمة مجمعة للعناصر المفقودة(مناسب للإكمال بعد إضافة لغة جديدة)

    \b
    سيقوم الترجمة بالتحقق من تطابق العناصر النائبة وأسطر الكسر مع النص الأصلي،ويقوم بالهروب التلقائي للاقتباسات؛
    سيتم إعادة محاولة العناصر التي فشلت في التحقق،إذا فشل مجددًا، لا تُكتب وتُدرج في النهاية.

    \b
    مثال:
        locale-tui translate-missing -l values-ar
        locale-tui translate-missing -m app --dry-run
    """
    run_batch_translation(
        **options,
        select=lambda source, existing: {
            k: v for k, v in source.items() if k not in existing
        },
        action="مفقود",
    )


@cli.command()
@click.option(
    "--match",
    "pattern",
    default=None,
    help="تعبير نظامي(تجاهل حالة الأحرف)،إعادة ترجمة الإدخالات المطابقة للنص الإنجليزي الأصلي",
)
@click.option("--key", "keys", multiple=True, help="بحسب key عنصر محدد،قابل للتكرار")
@batch_options
def retranslate(pattern: str | None, keys: tuple[str, ...], **options):
    """إعادة ترجمة إدخالات موجودة(مثلاً بعد تحديث معجم المصطلحات لتوحيد المصطلحات)

    \b
    استبدال مدخلات الموجودة فقط في لغة الهدف،للمداخل المفقودة استخدم translate-missing.
    --match و --key حدد واحدًا على الأقل،عند تحديدهما معًا، خذ الاتحاد.

    \b
    مثال:
        locale-tui retranslate -l values-ar --match '\\bprompts?\\b' --dry-run
        locale-tui retranslate -l values-ja --key setting_page_title
    """
    if not pattern and not keys:
        click.echo("خطأ:يرجى تحديد ما لا يقل عن --match أو --key", err=True)
        sys.exit(1)
    try:
        regex = re.compile(pattern, re.IGNORECASE) if pattern else None
    except re.error as e:
        click.echo(f"خطأ:تعبير نمطي غير صالح - {e}", err=True)
        sys.exit(1)

    def select(source: dict[str, str], existing: dict[str, str]) -> dict[str, str]:
        return {
            k: v
            for k, v in source.items()
            if k in existing and (k in keys or (regex and regex.search(v)))
        }

    run_batch_translation(**options, select=select, action="إعادة الترجمة")


def main():
    """Main entry point."""
    cli()


if __name__ == "__main__":
    main()
