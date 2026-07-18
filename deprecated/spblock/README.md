# SPBlock 方案（已废弃）

## 废弃时间
2026-07-18

## 原始设计
SPBlock（Safe Protection Block）通过把整棵树替换为不可破坏的代理方块来防止外部破坏。
包含：自定义方块、BlockEntity（存储原始BlockState）、动态模型（伪装成原始方块/蓝图虚影）、tint委托（树叶着色）。

## 废弃原因
1. 代码量过大：8个Java文件 + 渲染管线 + 着色管线，维护成本高
2. 过度设计：BFS一棵树的性能开销远低于维护自定义方块体系
3. 设计方向变更：借鉴TreeChop模组的懒BFS思路，改为每次砍伐时动态验证树结构

## 新方案（2026-07-18）
参见 ChopBehavior.java：
- 不锁定整棵树，每次砍前仅验证单点是否是原木
- 缓存失效时（方块被外部破坏）重新BFS更新列表
- LOG_BLOCKS Memory自身充当缓存层

## 保留原因
技术积累。如果未来需要在MiningBehavior中实现类似保护机制，SPBlock的动态模型+委托tint方案可复用。
