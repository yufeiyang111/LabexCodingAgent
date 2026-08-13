import assert from 'node:assert/strict'
import test from 'node:test'
import { isWaitingInputMisNarration } from './agentThinking.js'

test('drops legacy failure narrations of a waiting question', () => {
  assert.equal(isWaitingInputMisNarration('在 question 上遇到错误：正在等待用户输入。。需要换一种路径处理。'), true)
  assert.equal(isWaitingInputMisNarration('执行失败：正在等待用户输入。接下来会调整做法。'), true)
  assert.equal(isWaitingInputMisNarration('工具执行没有成功，关键信息是：正在等待用户输入。'), true)
  assert.equal(isWaitingInputMisNarration('Error on question: Waiting for user input. Trying alternative.'), true)
  assert.equal(isWaitingInputMisNarration('Command failed: Waiting for user input. Will adjust approach.'), true)
})

test('keeps legitimate thinking around questions', () => {
  assert.equal(isWaitingInputMisNarration('已收到用户输入，继续按新的信息执行。'), false)
  assert.equal(isWaitingInputMisNarration('当前需要用户补充一个关键决策，先发起提问。'), false)
  assert.equal(isWaitingInputMisNarration(''), false)
  assert.equal(isWaitingInputMisNarration('读取 README.md，先确认当前实现。'), false)
  assert.equal(isWaitingInputMisNarration(undefined), false)
})
