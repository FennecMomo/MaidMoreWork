package com.fennecmomo.maidmorework.api;

import com.fennecmomo.maidmorework.entity.ai.ISearchAction;
import net.minecraft.world.item.ItemStack;

public interface IMaidmoreHost
{
    ItemStack getInvItem(int slot);
    void setInvItem(int slot, ItemStack stack);
    int getHunger();
    void modifyHunger(int delta);
    ISearchAction getSearchAction();
    void setSearchAction(ISearchAction action);
    void clearSearchAction();
}
