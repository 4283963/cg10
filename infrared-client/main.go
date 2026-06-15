package main

import (
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math"
	"math/rand"
	"net"
	"sync"
	"time"
)

const (
	CylinderCount    = 40
	ScanPoints       = 64
	ScanFrequencyHz  = 100
	BaseTemp         = 105.0
	TempVariance     = 8.0
	AnomalyMagnitude = 15.0
	PayloadHeader    = "CG10"
)

type ScanPayload struct {
	Timestamp  int64     `json:"ts"`
	CylinderID int       `json:"cid"`
	ScanLine   []float64 `json:"temps"`
}

type CylinderSimulator struct {
	ID           int
	BaseTemp     float64
	LeftAnomaly  float64
	RightAnomaly float64
	CenterDip    float64
	Phase        float64
	mu           sync.Mutex
}

func NewCylinderSimulator(id int) *CylinderSimulator {
	return &CylinderSimulator{
		ID:           id,
		BaseTemp:     BaseTemp + rand.Float64()*4.0 - 2.0,
		LeftAnomaly:  0,
		RightAnomaly: 0,
		CenterDip:    0,
		Phase:        rand.Float64() * math.Pi * 2,
	}
}

func (c *CylinderSimulator) InjectAnomaly(side string, magnitude float64, duration time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	switch side {
	case "left":
		c.LeftAnomaly = magnitude
	case "right":
		c.RightAnomaly = magnitude
	case "center":
		c.CenterDip = magnitude
	}
	if duration > 0 {
		go func() {
			time.Sleep(duration)
			c.mu.Lock()
			defer c.mu.Unlock()
			switch side {
			case "left":
				c.LeftAnomaly = 0
			case "right":
				c.RightAnomaly = 0
			case "center":
				c.CenterDip = 0
			}
			log.Printf("[缸#%02d] 温度异常自动恢复", c.ID)
		}()
	}
}

func (c *CylinderSimulator) ScanLine() []float64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	temps := make([]float64, ScanPoints)
	c.Phase += 0.01
	for i := 0; i < ScanPoints; i++ {
		normX := float64(i) / float64(ScanPoints-1)
		temp := c.BaseTemp
		temp += math.Sin(c.Phase+float64(i)*0.2) * 1.5
		temp += rand.Float64()*0.6 - 0.3
		leftFalloff := math.Exp(-normX * 3.0)
		temp -= c.LeftAnomaly * leftFalloff
		rightFalloff := math.Exp(-(1.0-normX)*3.0)
		temp -= c.RightAnomaly * rightFalloff
		centerCurve := math.Exp(-math.Pow(normX-0.5, 2.0)*20.0)
		temp -= c.CenterDip * centerCurve
		temp = math.Max(60.0, math.Min(150.0, temp))
		temps[i] = math.Round(temp*100) / 100
	}
	return temps
}

type TCPSender struct {
	conn   net.Conn
	addr   string
	mu     sync.Mutex
	reconn chan struct{}
}

func NewTCPSender(addr string) *TCPSender {
	return &TCPSender{
		addr:   addr,
		reconn: make(chan struct{}, 1),
	}
}

func (t *TCPSender) Connect() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.conn != nil {
		t.conn.Close()
	}
	conn, err := net.DialTimeout("tcp", t.addr, 5*time.Second)
	if err != nil {
		return fmt.Errorf("连接失败: %w", err)
	}
	t.conn = conn
	log.Printf("已连接到后端 TCP 服务: %s", t.addr)
	return nil
}

func (t *TCPSender) Send(p *ScanPayload) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.conn == nil {
		return fmt.Errorf("未连接")
	}
	data, err := json.Marshal(p)
	if err != nil {
		return err
	}
	header := make([]byte, 8)
	copy(header[:4], []byte(PayloadHeader))
	binary.BigEndian.PutUint32(header[4:], uint32(len(data)))
	if _, err := t.conn.Write(header); err != nil {
		t.triggerReconnect()
		return err
	}
	if _, err := t.conn.Write(data); err != nil {
		t.triggerReconnect()
		return err
	}
	return nil
}

func (t *TCPSender) triggerReconnect() {
	select {
	case t.reconn <- struct{}{}:
	default:
	}
}

func (t *TCPSender) ReconnectLoop() {
	for range t.reconn {
		log.Println("尝试重连后端服务...")
		for i := 0; i < 60; i++ {
			if err := t.Connect(); err == nil {
				return
			}
			time.Sleep(1 * time.Second)
		}
		log.Fatal("重连失败次数过多，程序退出")
	}
}

func main() {
	serverAddr := flag.String("server", "127.0.0.1:9000", "后端TCP服务地址")
	flag.Parse()

	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	log.Println("=== 造纸机烘缸群红外扫描探头客户端启动 ===")
	log.Printf("烘缸数量: %d, 每缸扫描点数: %d, 扫描频率: %dHz",
		CylinderCount, ScanPoints, ScanFrequencyHz)

	cylinders := make([]*CylinderSimulator, CylinderCount)
	for i := 0; i < CylinderCount; i++ {
		cylinders[i] = NewCylinderSimulator(i + 1)
	}

	go func() {
		time.Sleep(8 * time.Second)
		log.Println("[仿真] 注入 #7 缸左侧边缘温度偏低故障")
		cylinders[6].InjectAnomaly("left", AnomalyMagnitude, 30*time.Second)
	}()
	go func() {
		time.Sleep(18 * time.Second)
		log.Println("[仿真] 注入 #23 缸中心温度凹陷故障")
		cylinders[22].InjectAnomaly("center", 10.0, 25*time.Second)
	}()
	go func() {
		time.Sleep(35 * time.Second)
		log.Println("[仿真] 注入 #35 缸右侧边缘温度偏低故障")
		cylinders[34].InjectAnomaly("right", AnomalyMagnitude, 20*time.Second)
	}()

	sender := NewTCPSender(*serverAddr)
	go sender.ReconnectLoop()
	for i := 0; i < 30; i++ {
		if connectErr := sender.Connect(); connectErr == nil {
			break
		} else {
			log.Printf("连接失败，%ds 后重试 (%d/30): %v", i+1, i+1, connectErr)
		}
		time.Sleep(time.Duration(i+1) * 500 * time.Millisecond)
		if i == 29 {
			log.Println("警告：后端未就绪，继续在后台尝试连接并生成模拟数据")
		}
	}

	interval := time.Second / time.Duration(ScanFrequencyHz)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	cylIdx := 0
	sentCount := 0
	lastReport := time.Now()

	for range ticker.C {
		ts := time.Now().UnixNano() / int64(time.Millisecond)
		payload := &ScanPayload{
			Timestamp:  ts,
			CylinderID: cylinders[cylIdx].ID,
			ScanLine:   cylinders[cylIdx].ScanLine(),
		}
		if err := sender.Send(payload); err != nil {
			if sentCount%100 == 0 {
				log.Printf("发送失败(已静默): %v", err)
			}
		} else {
			sentCount++
		}
		cylIdx = (cylIdx + 1) % CylinderCount
		if time.Since(lastReport) >= 5*time.Second {
			log.Printf("过去5秒已发送 %d 条扫描数据 (目标 %d 条)",
				sentCount, 5*ScanFrequencyHz)
			sentCount = 0
			lastReport = time.Now()
		}
	}
}
