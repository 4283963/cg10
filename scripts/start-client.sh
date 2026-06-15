#!/bin/bash
set -e

echo "========================================="
echo "  启动 Go 红外扫描探头客户端"
echo "========================================="

cd "$(dirname "$0")/../infrared-client"

if ! command -v go &> /dev/null; then
    echo "❌ 未检测到 Go，请先安装 Go 1.21+"
    exit 1
fi

if [ ! -f "./infrared-client" ]; then
    echo "▶️  编译 Go 客户端..."
    go build -o infrared-client .
fi

SERVER_ADDR="${1:-127.0.0.1:9000}"

echo ""
echo "▶️  启动红外扫描模拟器，连接后端: ${SERVER_ADDR}"
echo "   烘缸数: 40, 扫描频率: 100Hz"
echo "   故障仿真: 8秒后 #7缸左边缘低温, 18秒后 #23缸中心凹陷, 35秒后 #35缸右边缘低温"
echo ""

./infrared-client -server "${SERVER_ADDR}"
