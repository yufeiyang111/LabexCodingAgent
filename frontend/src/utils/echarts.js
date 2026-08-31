import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, HeatmapChart } from 'echarts/charts'
import {
  TooltipComponent,
  GridComponent,
  LegendComponent,
  VisualMapComponent,
  GraphicComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  HeatmapChart,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  VisualMapComponent,
  GraphicComponent,
  CanvasRenderer
])

export function loadEcharts() {
  return import('@/utils/echarts')
}

export default echarts
