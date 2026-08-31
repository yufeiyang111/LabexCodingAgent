export function headingId(text) {
  return String(text).toLowerCase().replace(/[^\w\u4e00-\u9fff -]/g, '').trim().replace(/\s+/g, '-')
}
