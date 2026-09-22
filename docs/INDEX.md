# MaidMoreWork 文档

> 版本：0.1.4 | MC 26.1.2 / NeoForge | JDK 25
> 全项目待办总览：[../../docs/TODO.md](../../docs/TODO.md)

Touhou Little Maid 扩展模组，为女仆新增伐木和采矿两种工作类型。

## 文档列表

| 文档 | 说明 |
|------|------|
| [API.md](API.md) | 公开 API 参考：MaidBubbleHelper、Project 体系、工程类型/区域管理器扩展、配置常量 |
| [CHANGELOG.md](CHANGELOG.md) | 版本更新记录（仅作者明确要求时记录） |

### 开发管理
| 文档 | 说明 |
|------|------|
| [issues/TODO_V1.md](issues/TODO_V1.md) | V1 待解决问题清单 |
| [issues/KNOWN_ISSUES.md](issues/KNOWN_ISSUES.md) | 长期技术难题（含已关闭记录） |

### 测试
| 文档 | 说明 |
|------|------|
| [issues/TEST_LOGGING.md](issues/TEST_LOGGING.md) | 当前测试清单（操作人用） |
| [issues/TEST_NOTES.md](issues/TEST_NOTES.md) | 测试笔记（AI 维护） |

### 设计文档
| 文档 | 说明 |
|------|------|
| [design/MINE_REDESIGN.md](design/MINE_REDESIGN.md) | 矿井重构设计（拍板记录 + 未实现项） |
| [design/MINE_SPIRAL_ALGORITHM.md](design/MINE_SPIRAL_ALGORITHM.md) | 矿井螺旋楼梯开挖算法与坐标公式 |

### Bug 记录
| 文档 | 说明 |
|------|------|
| [bugs/BUG-016.md](bugs/BUG-016.md) | Bug #16：背包满检测不触发存款 |

### 已废弃
| 文档 | 说明 |
|------|------|
| [deprecated/spblock.md](deprecated/spblock.md) | SPBlock 保护方块方案（2026-07-18 废弃） |

## 包结构

```
com.fennecmomo.maidmorework/
  MaidMoreWork.java                     # @Mod 入口
  MaidMoreWorkExtension.java            # TLM 扩展入口（ILittleMaid 实现）
  MaidBubbleHelper.java                 # 女仆气泡管理器（每女仆一实例，全局静态 Map 管理）
  MaidMoreWorkConfig.java               # 配置常量
  ModMemories.java                      # 自定义 Brain Memory 类型注册
  ModAttachments.java                   # 女仆持久化 Attachment 类型
  item/
    FluidBottleItem.java                # 液体瓶物品（动态流体瓶）
    MiningMaidSpawnEggItem.java         # 测试用挖矿女仆刷怪蛋
  lib/
    hud/
      HudLineRegistry.java              # HUD 行注册器
      IHudLine.java                     # HUD 行接口
    projecttype/
      IProjectType.java                 # 工程类型接口
      ProjectTypeRegistry.java          # 工程类型注册表
      RegProjectType.java               # 工程类型注册注解
    region/
      IRegionalManager.java             # 区域管理器接口
      RegionalManagerRegistry.java      # 区域管理器注册表
    render/
      BillboardRenderer.java            # 世界空间 Billboard 渲染
      WireframeBoxRenderer.java         # 线框盒渲染
  logging/
    ChopBehavior.java                   # 砍树行为
    LoggingExtraBrain.java              # 伐木 Brain 扩展（IExtraMaidBrain）
    LoggingTask.java                    # 伐木任务（IMaidTask）
  project/
    ProjectBase.java                    # 工程基类（多态 Codec 分派）
    CountingProject.java                # 计数型工程基类
    ChoppingProject.java                # 砍树工程
    ProjectClientHelper.java            # 客户端 HUD 数据拉取
    ProjectHudRenderer.java             # 工程进度世界空间面板渲染
    center/
      ProjectCenterBlock.java           # 工程中心方块
      ProjectCenterBlockEntity.java     # 工程中心方块实体（IRegionalManager）
      ProjectCenterInstance.java        # 工程中心后台实例
      ProjectCenterManager.java         # 全局实例管理器
      ProjectCenterData.java            # 持久化 SavedData
      ProjectCenterCommand.java         # /maidmorework minecenter 命令
      ProjectCenterMarkerItem.java      # 标记工具
      ProjectCenterMarkerEventHandler.java # 标记交互事件
      ProjectCenterRegistration.java    # 注册
      ProjectCenterActivationHud.java   # 激活面板
      ProjectCenterEditHud.java         # 编辑面板
      ProjectCenterEditPayload.java     # 编辑数据包
      ProjectCenterInfoPayload.java     # 中心信息广播包
      ProjectCenterBoundaryRenderer.java# 边界线框渲染
      MineInstance.java                 # 矿井后台实例（工程中心子类）
      WarehouseMenu.java                # 仓库菜单（一型一格/分页）
      WarehouseScreen.java              # 仓库界面
      WarehouseStorage.java             # 仓库聚合视图
      WarehouseClientRegistration.java  # 仓库客户端注册
    hud/
      ProjectHudPayload.java            # 服务端→客户端 HUD 数据包
      ProjectHudQueryPayload.java       # 客户端→服务端 HUD 请求包
    mine/
      MineCenterBlock.java              # 矿井方块（工程中心子类）
      MineCenterBlockEntity.java        # 矿井方块实体
      MineCenterBehavior.java           # 矿井挖矿行为（Home 模式）
      MineCenterCommand.java            # /maidmorework mine 命令
      MineCenterMarkerItem.java         # 矿井标记工具
      MineCenterMarkerEventHandler.java # 矿井标记交互
      MineCenterRegistration.java       # 矿井注册
      MineLayerControlBlock.java        # 矿道控制器方块
      MineLayerControlBlockEntity.java  # 矿道控制器方块实体
      MineMemberEventHandler.java       # 成员事件（死亡/卸载归还任务）
      MineTask.java                     # 带类型任务/困难表条目
      MineShaftProject.java             # 竖井层工程
      MineTunnelProject.java            # 横向矿道工程
      MiningTask.java                   # 采矿任务（IMaidTask）
      SpiralMinePlanner.java            # 螺旋楼梯规划算法
    type/
      ChoppingProjectType.java          # 砍树工程类型
      MiningProjectType.java            # 采矿工程类型
```
