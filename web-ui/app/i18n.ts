import i18n from "i18next";
import { initReactI18next } from "react-i18next";

import enUSCommon from "./locales/en-US/common.json";
import enUSInput from "./locales/en-US/input.json";
import enUSMarkdown from "./locales/en-US/markdown.json";
import enUSMessage from "./locales/en-US/message.json";
import enUSPage from "./locales/en-US/page.json";
import arCommon from "./locales/ar/common.json";
import arInput from "./locales/ar/input.json";
import arMarkdown from "./locales/ar/markdown.json";
import arMessage from "./locales/ar/message.json";
import arPage from "./locales/ar/page.json";

const SUPPORTED_LANGUAGES = ["ar", "en-US"] as const;

function getInitialLanguage(): (typeof SUPPORTED_LANGUAGES)[number] {
  if (typeof window === "undefined") {
    return "ar";
  }

  const fromStorage = window.localStorage.getItem("lang");
  if (fromStorage === "ar" || fromStorage === "en-US") {
    return fromStorage;
  }

  const browserLanguage = window.navigator.language;
  return browserLanguage.startsWith("ar") ? "ar" : "en-US";
}

void i18n.use(initReactI18next).init({
  resources: {
    ar: {
      common: arCommon,
      input: arInput,
      markdown: arMarkdown,
      message: arMessage,
      page: arPage,
    },
    "en-US": {
      common: enUSCommon,
      input: enUSInput,
      markdown: enUSMarkdown,
      message: enUSMessage,
      page: enUSPage,
    },
  },
  lng: getInitialLanguage(),
  fallbackLng: "ar",
  supportedLngs: [...SUPPORTED_LANGUAGES],
  defaultNS: "common",
  ns: ["common", "input", "markdown", "message", "page"],
  interpolation: {
    escapeValue: false,
  },
});

void i18n.on("languageChanged", (language) => {
  if (typeof window !== "undefined") {
    window.localStorage.setItem("lang", language);
  }
});

export default i18n;
