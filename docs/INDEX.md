# MaidMoreWork 文档

> 版本：0.0.1 | MC 26.1.2 / NeoForge | JDK 25

Touhou Little Maid 扩展模组，为女仆新增伐木和采矿两种工作类型。

## 文档列表

### 开发管理
| 文档 | 说明 |
|------|------|
| [issues/TODO_V1.md](issues/TODO_V1.md) | V1 待解决问题清单（挖矿/砍树/增强功能） |
| [issues/KNOWN_ISSUES.md](issues/KNOWN_ISSUES.md) | 3 项长期技术难题（动态渲染、GUI 弹窗、线框） |

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
  MaidBubbleHelper.java                 # 气泡生命周期管理
  ModMemories.java                      # 自定义 Brain Memory 类型
  ModAttachments.java                   # 持久化 Attachment 类型
  item/
    FluidBottleItem.java                # 液体瓶物品
  logging/
    LoggingTask.java                    # 伐木任务
    LoggingExtraBrain.java              # 伐木 Brain 注册
    ChopBehavior.java                   # 砍树行为
  mining/
    MiningTask.java                     # 采矿任务
    MiningExtraBrain.java               # 采矿 Brain 注册
    MiningBehavior.java                 # 采矿行为（状态机）
    MineBlock.java / MineBlockEntity.java  # 矿井方块与数据
    MineInstance.java / MineInstanceManager.java  # 矿井实例管理
    MineMarkerItem.java / MineMarkerEventHandler.java  # 标记工具
    MineCommand.java                    # /maidmorework mine 命令
    MineRegistration.java               # 注册中心
    MineStorageMenu.java / MineStorageScreen.java  # 储物界面
    SpiralMinePlanner.java              # 螺旋规划算法
    DigTask.java                        # 挖掘任务记录
  project/
    ProjectBase.java                    # 项目基类（Codec 多态）
    CountingProject.java                # 计数后批量执行
    ChoppingProject.java                # 砍树项目
    ProjectManager.java                 # 项目生命周期管理
    ProjectData.java                    # SavedData 持久化
  search/
    SearchBehavior.java                 # 螺旋搜索行为
    ISearchAction.java                  # 搜索动作接口
```
