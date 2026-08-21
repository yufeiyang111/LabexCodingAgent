import { ElMessageBox } from 'element-plus'
import { monitorApi } from '@/api'

/** 受控操作统一封装：只读会话（OPS_VIEWER）执行操作时自动弹窗索取操作码并按请求提权，本会话内记住。 */
export function useMonitorOperator() {
  async function withOperator(fn) {
    const body = { operatorCode: monitorApi.getOperatorCode() || undefined }
    try {
      return await fn(body)
    } catch (e) {
      if (e?.status !== 403) {
        throw e
      }
      let value = ''
      try {
        const result = await ElMessageBox.prompt(
          '当前会话为只读角色，请输入操作者校验码以执行此操作（本会话内将记住）',
          '需要操作权限',
          { inputType: 'password', confirmButtonText: '确认', cancelButtonText: '取消' }
        )
        value = result.value
      } catch (_) {
        throw new Error('已取消操作')
      }
      if (!value) {
        throw new Error('未输入操作码')
      }
      monitorApi.setOperatorCode(value)
      return await fn({ ...body, operatorCode: value })
    }
  }

  return { withOperator }
}