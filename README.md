# 女仆的更多工作 (MaidMoreWork)

为[车万女仆 (Touhou Little Maid)](https://github.com/TartaricAcid/TouhouLittleMaid) 模组增加更多工作类型。

## 当前功能

### 伐木 (Logging)

女仆自动搜索家园范围内的树木，创建砍树工程并持续砍伐。

- 螺旋搜索算法从女仆当前位置向外扩展，寻找带邻叶的原木方块
- BFS 收集整棵树的连通原木，创建砍树工程
- 按原版公式计算砍伐速度，斧子材质越好速度越快
- 支持多名女仆协作砍树（孤儿工程接盘机制）
- 自动装备背包中最好的斧子，支持副手检测
- 砍树过程中工具耐久正常扣减
- 右上角 HUD 面板实时显示工程进度

### 挖矿 (Mining)

女仆在矿井范围内执行螺旋形阶梯式挖掘。

- 自定义矿井方块 + 标记工具划定开采区域
- 螺旋形阶梯规划算法，逐层向下挖掘
- 矿井存储系统，27 格超大堆叠（640/格）
- 支持挖掘、填充、照明、替换四种子任务

## 依赖

- Minecraft 26.1.2+
- NeoForge 26.1.2+
- [车万女仆 (Touhou Little Maid)](https://github.com/TartaricAcid/TouhouLittleMaid) 2.0.0+
- [MomoLib](https://github.com/FennecMomo/MomoLib)

## 安装

将 jar 文件放入 `.minecraft/mods/` 目录即可。

## 开发

```bash
git clone https://github.com/FennecMomo/MaidMoreWork.git
cd MaidMoreWork
./gradlew build
```

## 许可证

MIT License - 详见 [LICENSE](./LICENSE)
