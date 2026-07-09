package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VisibleLanguageResolverTest {
    @Test
    void detectsDominantUserLanguageAcrossMixedInputs() {
        assertThat(VisibleLanguageResolver.resolve("请帮我修复 npm run build 报错", "en").code()).isEqualTo("zh");
        assertThat(VisibleLanguageResolver.resolve("Please fix the login button 点击无反应", "zh").code()).isEqualTo("en");
        assertThat(VisibleLanguageResolver.resolve("このエラーを修正してください", "en").code()).isEqualTo("ja");
        assertThat(VisibleLanguageResolver.resolve("로그인 오류를 고쳐줘", "en").code()).isEqualTo("ko");
    }

    @Test
    void inheritsPreviousLanguageForCodeOnlyInput() {
        assertThat(VisibleLanguageResolver.resolve("npm run build", "zh").code()).isEqualTo("zh");
        assertThat(VisibleLanguageResolver.resolve("/init", "ja").code()).isEqualTo("ja");
        assertThat(VisibleLanguageResolver.resolve("git status", null).code()).isEqualTo("en");
    }
}
