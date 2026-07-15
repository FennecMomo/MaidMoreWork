package com.fennecmomo.maidmorework.api;

import com.fennecmomo.maidmorework.entity.ai.ISearchAction;
import net.minecraft.world.item.ItemStack;

// 女仆扩展接口（Mixin 注入）
//
// 提供背包访问、饥饿度管理和搜索行为绑定
// 由 EntityMaid 的 Mixin 实现，让 maidmorework 的行为可以直接访问女仆的扩展数据
//
// 用途：
//   EatTask — 读取背包食物 + 修改饥饿度
//   PickupBehavior — 把拾取的物品放入背包
//   SearchBehavior — 绑定当前搜索行为（伐木/挖矿）
public interface IMaidmoreHost
{
    // 获取背包指定槽位的物品（背包共 27 格，0~26）
    ItemStack getInvItem(int slot);
    // 设置背包指定槽位的物品（空槽位用 ItemStack.EMPTY）
    void setInvItem(int slot, ItemStack stack);
    // 获取当前饥饿度（0~100，低于 20 时触发吃东西行为）
    int getHunger();
    // 修改饥饿度（正数加，负数减，上限 100）
    void modifyHunger(int delta);
    // 获取当前搜索行为（伐木/挖矿任务激活时设置）
    ISearchAction getSearchAction();
    // 设置搜索行为（任务启动时调用）
    void setSearchAction(ISearchAction action);
    // 清空搜索行为（任务停止时调用）
    void clearSearchAction();
}
