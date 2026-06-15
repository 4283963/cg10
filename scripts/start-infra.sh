#!/bin/bash
set -e

echo "========================================="
echo "  CG10 造纸机烘缸群温度监控系统"
echo "  基础设施启动脚本 (InfluxDB)"
echo "========================================="

cd "$(dirname "$0")/.."

if ! command -v docker &> /dev/null; then
    echo "❌ 未检测到 Docker，请先安装 Docker Desktop"
    exit 1
fi

if ! docker compose version &> /dev/null; then
    echo "❌ 未检测到 docker compose，请升级 Docker"
    exit 1
fi

echo ""
echo "▶️  启动 InfluxDB 时序数据库..."
docker compose up -d influxdb

echo ""
echo "⏳ 等待 InfluxDB 就绪 (约 10 秒)..."
sleep 10

for i in {1..20}; do
    if curl -s http://127.0.0.1:8086/ping &> /dev/null; then
        echo "✅ InfluxDB 已启动成功"
        break
    fi
    echo "  等待中... (${i}/20)"
    sleep 2
done

echo ""
echo "📋 InfluxDB 访问信息:"
echo "   地址: http://127.0.0.1:8086"
echo "   用户: admin"
echo "   密码: cg10paper2024"
echo "   组织: paper-mill"
echo "   存储桶: dryer-temperatures"
echo "   Token: cg10-secret-token-2024"
