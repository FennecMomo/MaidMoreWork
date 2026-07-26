# MaidMoreWork 文档

> 版本：0.0.1 | MC 26.1.2 / NeoForge | JDK 25

Touhou Little Maid 扩展模组，为女仆新增伐木和采矿两种工作类型。

## 文档列表

| 文档 | 说明 |
|------|------|
| [API.md](API.md) | 公开 API 参考：SearchBehavior、MaidBubbleHelper、ProjectServerHelper/ProjectClientHelper、Project 体系等 |

### 开发管理
| 文档 | 说明 |
|------|------|
| [issues/TODO_V1.md](issues/TODO_V1.md) | V1 待解决问题清单 |
| [issues/KNOWN_ISSUES.md](issues/KNOWN_ISSUES.md) | 长期技术难题 |

### 设计文档
| 文档 | 说明 |
|------|------|
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
  MaidMoreWorkExtension.java            # TLM 扩展注册
  MaidBubbleHelper.java                 # 气泡管理器
  MaidMoreWorkConfig.java               # 配置常量
  ModMemories.java                      # 自定义 Brain Memory 类型
  ModAttachments.java                   # 持久化 Attachment 类型
  item/
    FluidBottleItem.java                # 液体瓶物品
  logging/
    LoggingTask.java                    # 伐木任务（IMaidTask）
    LoggingExtraBrain.java              # 伐木 Brain 注册
    ChopBehavior.java                   # 砍树行为
  mining/
    MiningTask.java                     # 采矿任务（IMaidTask）
    MiningExtraBrain.java               # 采矿 Brain 注册
    MiningBehavior.java                 # 采矿行为
    MineBlock.java                      # 矿井方块
    MineBlockEntity.java                # 矿井方块实体
    MineInstance.java                   # 矿井实例
    MineInstanceManager.java            # 矿井实例管理器
    MineMarkerItem.java                 # 矿井标记工具
    MineMarkerEventHandler.java         # 标记交互处理
    MineCommand.java                    # /maidmorework mine 命令
    MineRegistration.java               # 服务端注册
    MineClientRegistration.java         # 客户端注册
    MineStorageMenu.java                # 矿井储物菜单
    MineStorageScreen.java              # 矿井储物界面
    SpiralMinePlanner.java              # 螺旋规划算法
    DigTask.java                        # 挖掘任务记录
  project/
    ProjectBase.java                    # 工程基类
    CountingProject.java                # 计数型工程
    ChoppingProject.java                # 砍树工程
    ProjectServerHelper.java            # 服务端工程管理
    ProjectClientHelper.java            # 客户端 HUD 渲染 + 拉数据
    ProjectData.java                    # SavedData 持久化
    hud/
      ProjectHudPayload.java            # 服务端→客户端 HUD 数据包
      ProjectHudQueryPayload.java       # 客户端→服务端 HUD 请求包
      HudLineRegistry.java              # HUD 行注册器
      IHudLine.java                     # HUD 行接口
  search/
    SearchBehavior.java                 # 螺旋搜索行为
    ISearchAction.java                  # 搜索动作接口
```
