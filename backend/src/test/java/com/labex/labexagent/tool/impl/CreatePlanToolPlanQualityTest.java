package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 计划项质量校验：拒绝含糊计划（含糊计划是死循环的主要来源）。 */
class CreatePlanToolPlanQualityTest {

    @Test
    void acceptsSpecificVerifiablePlanItems() {
        assertThat(CreatePlanTool.validatePlanItem(0, "Verify frontend build")).isNull();
        assertThat(CreatePlanTool.validatePlanItem(0, "Add a /api/data endpoint in app.py that returns JSON")).isNull();
        assertThat(CreatePlanTool.validatePlanItem(0, "修复登录")).isNull();
        assertThat(CreatePlanTool.validatePlanItem(0, "Read app.py to understand current route structure")).isNull();
    }

    @Test
    void rejectsVaguePlanItems() {
        assertThat(CreatePlanTool.validatePlanItem(0, "Optimize the code")).isNotNull();
        assertThat(CreatePlanTool.validatePlanItem(0, "Make it better")).isNotNull();
        assertThat(CreatePlanTool.validatePlanItem(0, "Test everything")).isNotNull();
        assertThat(CreatePlanTool.validatePlanItem(0, "优化代码")).isNotNull();
        assertThat(CreatePlanTool.validatePlanItem(0, "修复")).isNotNull();
    }
}
