import axios from 'axios'

const api = axios.create({
  baseURL: '/',
  timeout: 10000
})

export { api }

export const wsUrl = '/ws/heatmap'
