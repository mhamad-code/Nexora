import {
  copyFileSync,
  mkdirSync,
  readdirSync,
  rmSync,
  statSync,
} from "node:fs";
import { join, dirname } from "node:path";

const SOURCE_DIR = "./build/client";
const TARGET_DIR = "../web/src/main/resources/static";

function copyDirectory(src: string, dest: string) {
  // تأكد من وجود المجلد الهدف
  mkdirSync(dest, { recursive: true });

  const entries = readdirSync(src, { withFileTypes: true });

  for (const entry of entries) {
    const srcPath = join(src, entry.name);
    const destPath = join(dest, entry.name);

    if (entry.isDirectory()) {
      copyDirectory(srcPath, destPath);
    } else {
      // تأكد من وجود الدليل الأب
      mkdirSync(dirname(destPath), { recursive: true });
      copyFileSync(srcPath, destPath);
    }
  }
}

try {
  console.log("📦 Starting build output copy...");
  console.log(`   Source: ${SOURCE_DIR}`);
  console.log(`   Target: ${TARGET_DIR}`);

  // تحقق من وجود دليل المصدر
  try {
    statSync(SOURCE_DIR);
  } catch {
    console.error(`❌ Source directory not found: ${SOURCE_DIR}`);
    console.error("   Please run build first.");
    process.exit(1);
  }

  // تنظيف دليل الهدف(إن وُجد)
  try {
    rmSync(TARGET_DIR, { recursive: true, force: true });
    console.log("🧹 Cleaned target directory");
  } catch (err) {
    // المجلد الهدف غير موجود،تجاهل الأخطاء
  }

  // نسخ ملف
  copyDirectory(SOURCE_DIR, TARGET_DIR);

  console.log("✅ Build output copied successfully!");
} catch (error) {
  console.error("❌ Copy failed:", error);
  process.exit(1);
}
