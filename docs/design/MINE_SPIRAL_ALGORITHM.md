# 矿井螺旋楼梯开挖算法

> 来源：`矿井逻辑方案.txt` | 转换为 Markdown

## 坐标系

```
        -Z
  -X          X
        Z
```

## 参数定义

- 矿井方块坐标：`[x, y, z]`
- 矿区范围：`(L, W)`
- 西北角：`posNW = [x - (L-1)/2, y, z - (W-1)/2]`
- 东南角：`posSE = [x + (L-1)/2, y, z + (W-1)/2]`

## 周期与边

```
周期 C
周期中第 n 条边（北 0 → 西 1 → 南 2 → 东 3）

南北高低落差 hofNS = L - 5
东西高低落差 hofWE = W - 5
```

## 周期内高度偏差 h

```
实际坐标 H = y - (hofNS + hofWE) * 2 * C - h

if (n >= 1) H -= hofNS
if (n >= 2) H -= hofWE
if (n == 3) H -= hofNS
```

## 半边长度

```
南北半边长度 halfL = (L - 1) / 2
东西半边长度 halfW = (W - 1) / 2
```

## 索引与高度偏移

```
if (n % 2 == 0)
{
    idx = 0       → h = idx
    idx = 1 ~ halfL-1 → h = idx - 1
    idx = halfL   → h = idx - 2
    idx = halfL+1 ~ L-3 → h = idx - 3
    if (idx > L-3) n += 1
}
else
{
    idx = 0       → h = idx
    idx = 1 ~ halfW-1 → h = idx - 1
    idx = halfW   → h = idx - 2
    idx = halfW+1 ~ W-3 → h = idx - 3
    if (idx > W-3) n += 1
    if (n > 3) C += 1, n = 0
}
```

## 四向保留区

### 北向 n=0
```
idx = 0      : [posSE.x, H, posNW.z] ~ [posSE.x, H, posNW.z + 1]
idx = 1 ~ L-3 : [posSE.x - idx, H, posNW.z] ~ [posSE.x - idx + 1, H, posNW.z + 1]
```

### 西向 n=1
```
idx = 0      : [posNW.x, H, posNW.z] ~ [posNW.x + 1, H, posNW.z]
idx = 1 ~ W-3 : [posNW.x, H, posNW.z + idx] ~ [posNW.x + 1, H, posNW.z + idx - 1]
```

### 南向 n=2
```
idx = 0      : [posNW.x, H, posSE.z] ~ [posNW.x, H, posSE.z - 1]
idx = 1 ~ L-3 : [posNW.x + idx, H, posSE.z] ~ [posNW.x + idx - 1, H, posSE.z - 1]
```

### 东向 n=3
```
idx = 0      : [posSE.x, H, posSE.z] ~ [posSE.x - 1, H, posSE.z]
idx = 1 ~ W-3 : [posSE.x, H, posSE.z - idx] ~ [posSE.x - 1, H, posSE.z - idx + 1]
```
