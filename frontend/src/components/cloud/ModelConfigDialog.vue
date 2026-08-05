<template>
    <!-- Model Config Modal -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="state.showModelConfig" class="ws-overlay" @click.self="state.showModelConfig = false">
          <div class="mc-dialog">
            <div class="mc-header">
              <h3>模型配置</h3>
              <button class="mc-close-btn" @click="state.showModelConfig = false">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>

            <!-- Template Selection View -->
            <div v-if="state.mcTemplateSelecting" class="mc-body">
              <div class="mc-template-grid">
                <button v-for="tpl in state.mcTemplateOptions" :key="tpl.name" class="mc-template-card" @click="actions.selectModelTemplate(tpl)">
                  <span class="mc-template-icon" :style="{ background: tpl.accent }">{{ tpl.iconText }}</span>
                  <span class="mc-template-main">
                    <span class="mc-template-name">{{ tpl.name }}</span>
                    <span class="mc-template-vendor">{{ tpl.vendor }}</span>
                    <span class="mc-template-model">{{ tpl.modelName || '手动填写模型名称' }}</span>
                  </span>
                  <span v-if="tpl.modelsUrl" class="mc-template-source">官方列表</span>
                </button>
              </div>
              <div class="mc-form-actions">
                <button class="mc-btn mc-btn-outline" @click="state.mcTemplateSelecting = false">返回</button>
              </div>
            </div>

            <!-- Config List View -->
            <div v-else-if="!state.mcEditing" class="mc-body">
              <div class="mc-config-list">
                <div v-if="state.modelConfigs.length === 0" class="mc-empty">
                  <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#d1d5db" stroke-width="1.5"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                  <p>暂无模型配置</p>
                  <p class="mc-empty-hint">添加一个模型配置开始使用 AI 助手</p>
                </div>
                <div v-for="cfg in state.modelConfigs" :key="cfg.configId" class="mc-config-card" :class="{ active: state.selectedModelConfigId === cfg.configId, default: cfg.isDefault === 1 }">
                  <div class="mc-card-main" @click="state.selectedModelConfigId = cfg.configId">
                    <div class="mc-card-top">
                      <span class="mc-card-name">{{ cfg.configName }}</span>
                      <span v-if="cfg.isDefault === 1" class="mc-badge-default">默认</span>
                    </div>
                    <div class="mc-card-meta">
                      <span class="mc-card-provider">{{ cfg.provider || 'openai_compatible' }}</span>
                      <span class="mc-card-sep">|</span>
                      <span class="mc-card-model">{{ cfg.modelName || 'gpt-4o-mini' }}</span>
                    </div>
                    <div class="mc-card-url" v-if="cfg.baseUrl">{{ cfg.baseUrl }}</div>
                    <!-- Test Result -->
                    <div v-if="state.mcTestResults[cfg.configId]" class="mc-test-result" :class="state.mcTestResults[cfg.configId].success ? 'mc-test-ok' : 'mc-test-fail'">
                      <svg v-if="state.mcTestResults[cfg.configId].success" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                      <svg v-else width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
                      <span v-if="state.mcTestResults[cfg.configId].success">连接成功 {{ state.mcTestResults[cfg.configId].latency }}ms</span>
                      <span v-else>{{ state.mcTestResults[cfg.configId].error }}</span>
                    </div>
                  </div>
                  <div class="mc-card-actions">
                    <button class="mc-action-btn mc-action-test" title="测试连接" @click.stop="actions.testConfig(cfg)" :disabled="state.mcTestingIds[cfg.configId]">
                      <svg v-if="state.mcTestingIds[cfg.configId]" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="mc-spin"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
                      <svg v-else width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
                    </button>
                    <button class="mc-action-btn" title="编辑" @click="actions.editConfig(cfg)">
                      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                    </button>
                    <button class="mc-action-btn mc-action-delete" title="删除" @click="actions.deleteConfig(cfg)">
                      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                    </button>
                  </div>
                </div>
              </div>
              <button class="mc-add-btn" @click="actions.startCreateConfig">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                <span>添加配置</span>
              </button>
            </div>

            <!-- Config Edit/Create View -->
            <div v-else class="mc-body">
              <div class="mc-form">
                <div class="mc-field">
                  <label>配置名称 <span class="mc-required">*</span></label>
                  <input v-model="state.mcForm.configName" class="mc-input" placeholder="例如：我的 DeepSeek" />
                </div>
                <div class="mc-field">
                  <label>提供商</label>
                  <select v-model="state.mcForm.provider" class="mc-select">
                    <option value="openai_compatible">OpenAI Compatible</option>
                  </select>
                </div>
                <div class="mc-field">
                  <label>模型名称 <span class="mc-required">*</span></label>
                  <input v-model="state.mcForm.modelName" class="mc-input" placeholder="deepseek-chat" />
                </div>
                <div class="mc-field">
                  <label>{{ state.mcCustomMode ? '模型列表 URL' : '官方模型列表' }}</label>
                  <div class="mc-field-action-row">
                    <input v-model="state.mcForm.modelsUrl" class="mc-input" placeholder="https://.../v1/models" />
                    <button class="mc-btn mc-btn-outline mc-btn-small" @click="actions.fetchModelList" :disabled="state.mcModelsLoading">
                      {{ state.mcModelsLoading ? '获取中...' : '获取模型' }}
                    </button>
                  </div>
                  <div class="mc-hint">{{ state.mcCustomMode ? '仅自定义配置需要填写模型列表 URL' : '从厂商官方模型列表接口读取可用模型；多数服务需要先填写 API Key' }}</div>
                  <select v-if="state.mcFetchedModels.length > 0" v-model="state.mcForm.modelName" class="mc-select mc-model-select" @change="actions.applyFetchedModelLimits">
                    <option v-for="model in state.mcFetchedModels" :key="model.id" :value="model.id">{{ model.id }}{{ model.maxTokens ? ` (${model.maxTokens})` : '' }}</option>
                  </select>
                </div>
                <div class="mc-field">
                  <label>API Key / Token Plan Key <span class="mc-required">*</span></label>
                  <input v-model="state.mcForm.apiKey" class="mc-input" type="password" :placeholder="state.mcApiKeyHint ? '已设置 (' + state.mcApiKeyHint + ')，留空则保持不变' : 'sk-...'" autocomplete="new-password" />
                  <div v-if="state.mcApiKeyHint && !state.mcForm.apiKey" class="mc-hint mc-hint-ok">API Key 已设置，留空将保持原值不变</div>
                </div>
                <div class="mc-row">
                  <div class="mc-field mc-field-half">
                    <label>Max Tokens</label>
                    <input v-model.number="state.mcForm.maxTokens" class="mc-input" type="number" min="1" step="1" placeholder="32768" />
                    <div class="mc-hint">单次输出的最大 Token 数，不包含输入上下文</div>
                  </div>
                  <div class="mc-field mc-field-half">
                    <label>Temperature</label>
                    <input v-model.number="state.mcForm.temperature" class="mc-input" type="number" step="0.1" min="0" max="2" placeholder="0.7" />
                    <div class="mc-hint">0=确定性 2=高随机</div>
                  </div>
                </div>
                <div class="mc-row">
                  <div class="mc-field mc-field-half">
                    <label>推理程度</label>
                    <select v-model="state.mcForm.reasoningEffort" class="mc-select">
                      <option value="low">低（更快、更省）</option>
                      <option value="medium">中（推荐）</option>
                      <option value="high">高（更深入）</option>
                      <option value="xhigh">超高（最深入、成本更高）</option>
                    </select>
                    <div class="mc-hint">仅在服务端支持时发送 <code>reasoning_effort</code>；不支持会安全降级。</div>
                  </div>
                  <div class="mc-field mc-field-half">
                    <label>多模态能力</label>
                    <label class="mc-checkbox-row">
                      <input type="checkbox" v-model="state.mcForm.imageInputEnabled" />
                      <span>支持图片理解</span>
                    </label>
                    <div class="mc-hint">仅为具备视觉输入能力的模型开启；开启后 Agent 的图片工具会使用这套模型配置。</div>
                  </div>
                </div>
                <div class="mc-field">
                  <label>上下文窗口 Tokens</label>
                  <input v-model.number="state.mcForm.contextWindowTokens" class="mc-input" type="number" min="1" step="1" placeholder="请填写真实值" />
                  <div class="mc-hint">模型单次请求可容纳的总输入 + 输出 Token 容量，必须大于 Max Tokens</div>
                </div>
                <div class="mc-field mc-context-policy">
                  <label>上下文压缩策略</label>
                  <label class="mc-checkbox-row">
                    <input type="checkbox" v-model="state.mcForm.compactionAuto" />
                    <span>接近上下文上限时自动压缩</span>
                  </label>
                  <label class="mc-checkbox-row">
                    <input type="checkbox" v-model="state.mcForm.compactionPrune" :disabled="!state.mcForm.compactionAuto" />
                    <span>优先清理可重新获取的旧工具结果</span>
                  </label>
                  <div class="mc-row">
                    <div class="mc-field mc-field-half">
                      <label>最近保留回合</label>
                      <input v-model.number="state.mcForm.compactionTailTurns" class="mc-input" type="number" min="1" step="1" />
                    </div>
                    <div class="mc-field mc-field-half">
                      <label>最近上下文 Token 预算</label>
                      <input v-model.number="state.mcForm.compactionPreserveRecentTokens" class="mc-input" type="number" min="1" step="1" placeholder="自动" />
                    </div>
                  </div>
                  <div class="mc-field">
                    <label>自动压缩触发阈值 (%)</label>
                    <input v-model.number="state.mcForm.compactionThresholdPercent" class="mc-input" type="number" min="70" max="99" step="1" />
                    <div class="mc-hint">百分比阈值只是上限之一；最终有效线还会同时受 Max Tokens 输出预留和安全缓冲约束。</div>
                  </div>
                  <div class="mc-field">
                    <label>压缩安全缓冲 Tokens</label>
                    <input v-model.number="state.mcForm.compactionReservedTokens" class="mc-input" type="number" min="0" step="1" placeholder="自动（总窗口的 10%）" />
                    <div class="mc-hint">作为总窗口末尾的安全上限；空值时按总窗口 10% 计算，并限制在 2,048 到 8,192 tokens。</div>
                  </div>
                  <div class="mc-policy-preview" :class="{ invalid: !effectivePolicy.valid }">
                    <strong>有效自动压缩线</strong>
                    <template v-if="effectivePolicy.valid">
                      <span>{{ formatPolicyTokens(effectivePolicy.softLimitTokens) }} tokens（占总上下文窗口 {{ effectivePolicy.softLimitPercent.toFixed(1) }}%）</span>
                      <small>输入容量 {{ formatPolicyTokens(effectivePolicy.inputCapacityTokens) }}；百分比线 {{ formatPolicyTokens(effectivePolicy.thresholdLimitTokens) }}；安全缓冲线 {{ formatPolicyTokens(effectivePolicy.reserveLimitTokens) }}。系统取三者最小值。</small>
                      <small v-if="!state.mcForm.compactionAuto">自动压缩当前已关闭；该数值仍用于解释输入预算。</small>
                    </template>
                    <span v-else>{{ effectivePolicy.reason }}</span>
                  </div>
                  <div class="mc-field">
                    <label>压缩专用模型</label>
                    <select v-model.number="state.mcForm.compactionModelConfigId" class="mc-input">
                      <option :value="0">沿用当前 Agent 模型</option>
                      <option v-for="config in state.modelConfigs" :key="config.configId" :value="config.configId">
                        {{ config.configName || config.modelName }}（{{ config.modelName }}）
                      </option>
                    </select>
                    <div class="mc-hint">压缩摘要只会调用该模型且不会获得工具、终端、文件或 MCP 权限。</div>
                  </div>
                </div>
                <label class="mc-checkbox-row">
                  <input type="checkbox" v-model="state.mcForm.promptCacheKeyEnabled" />
                  <span>启用 Prompt Cache Key</span>
                </label>
                <div class="mc-hint">仅在供应商明确支持 <code>prompt_cache_key</code> 时启用；系统会按模型配置、稳定系统提示和工具定义生成不可逆缓存路由键，提升多轮工具调用的 KV Cache 命中率。</div>
                <label class="mc-checkbox-row">
                  <input type="checkbox" v-model="state.mcForm.isDefault" />
                  <span>设为默认模型</span>
                </label>
              </div>
              <div class="mc-form-actions">
                <button class="mc-btn mc-btn-outline" @click="actions.cancelModelConfigEdit">取消</button>
                <button class="mc-btn mc-btn-primary" @click="actions.saveConfig" :disabled="state.mcSaving">{{ state.mcSaving ? '保存中...' : (state.mcEditingId ? '更新' : '创建') }}</button>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

</template>

<script setup>
import { computed } from 'vue'
import { calculateContextWindowPolicy } from '@/composables/contextWindowPolicyView'

const props = defineProps({
  state: { type: Object, required: true },
  actions: { type: Object, required: true }
})

const effectivePolicy = computed(() => calculateContextWindowPolicy(props.state?.mcForm || {}))
const formatPolicyTokens = value => Number(value || 0).toLocaleString('zh-CN')
</script>
