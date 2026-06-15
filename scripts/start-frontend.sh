#!/bin/bash
set -e

echo "========================================="
echo "  启动 Vue3 前端大屏"
echo "========================================="

cd "$(dirname "$0")/../heatmap-frontend"

if ! command -v node &> /dev/null; then
    echo "❌ 未检测到 Node.js，请先安装 Node.js 18+"
    exit 1
fi

if [ ! -d "node_modules" ]; then
    echo "▶️  安装前端依赖..."
    npm install
fi

echo ""
echo "▶️  启动前端开发服务器 (端口 5173)..."
echo "   访问地址: http://127.0.0.1:5173"
echo ""

npm run dev
