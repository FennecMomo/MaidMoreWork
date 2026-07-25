# Git 提交规范

## 提交消息格式

```
类型: 简短中文描述（一行，不超过 72 字符）

新增:
- 功能1: 说明
- 功能2: 说明

修改:
- 文件或模块: 改动说明
- 文件或模块: 改动说明

删除:
- 文件或模块: 说明
```

## 提交类型

| 类型 | 用途 |
|------|------|
| 新增 | 新功能、新文件、新模块 |
| 修改 | 现有功能或逻辑的变更 |
| 删除 | 移除文件、模块或废弃代码 |
| 修复 | 修 bug |
| 重构 | 纯结构优化，不改功能 |

## 示例

```
重构: 工程系统持久化升级为Codec方案 + 行为层职责分离

新增:
- ProjectData (SavedData): 使用Codec+SavedDataType实现声明式持久化
- ProjectBase: 多态Codec dispatch分派机制

修改:
- ProjectManager: 集成SavedData懒加载+脏标记同步
- ChopBehavior: canStillUse合并检查+stopChop重命名+简化导航

删除:
- 旧CompoundTag手动序列化代码
```
