#!/bin/bash
set -e

echo "========================================="
echo "  启动 Java Spring Boot 后端服务"
echo "========================================="

cd "$(dirname "$0")/../tcp-server"

if ! command -v mvn &> /dev/null; then
    echo "❌ 未检测到 Maven，请先安装 Maven 3.8+"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | grep -Eo '[0-9]+' | head -1)
echo "ℹ️  检测到 Java 版本: ${JAVA_VERSION}"

if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "❌ 需要 Java 17 或更高版本"
    exit 1
fi

echo ""
echo "▶️  编译后端服务..."
mvn package -DskipTests -q

echo ""
echo "▶️  启动后端服务 (端口 8080)..."
echo "   TCP 端口: 9000 (接收Go客户端数据)"
echo "   HTTP API: http://127.0.0.1:8080/api"
echo "   WebSocket: ws://127.0.0.1:8080/ws/heatmap"
echo ""

java -jar target/paper-dryer-monitor-1.0.0.jar
