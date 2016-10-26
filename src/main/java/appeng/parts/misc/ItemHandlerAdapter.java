/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.parts.misc;


import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.core.AELog;
import appeng.me.storage.ITickingMonitor;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;


/**
 * Wraps an Item Handler in such a way that it can be used as an IMEInventory for items.
 */
class ItemHandlerAdapter implements IMEInventory<IAEItemStack>, IBaseMonitor<IAEItemStack>, ITickingMonitor
{

	private final Map<IMEMonitorHandlerReceiver<IAEItemStack>, Object> listeners = new HashMap<>();

	private BaseActionSource mySource;

	private final IItemHandler itemHandler;

	private ItemStack[] cachedStacks = new ItemStack[0];

	private IAEItemStack[] cachedAeStacks = new IAEItemStack[0];

	ItemHandlerAdapter( IItemHandler itemHandler )
	{
		this.itemHandler = itemHandler;
	}

	@Override
	public IAEItemStack injectItems( IAEItemStack iox, Actionable type, BaseActionSource src )
	{

		ItemStack orgInput = iox.getItemStack();
		ItemStack remaining = orgInput;

		// In simulation mode, we don't need to do 2 passes, since if we find an empty slot, we would
		// eventually insert into it in the second phase. So for the sake of a simulation, we can consider it
		// immediately.
		if( type == Actionable.SIMULATE )
		{
			remaining = simulateInject( remaining );
		}
		else
		{
			remaining = performInject( remaining );
		}

		// At this point, we still have some items left...
		if( remaining == orgInput )
		{
			// The stack remained unmodified, target inventory is full
			return iox;
		}

		if( type == Actionable.MODULATE )
		{
			this.onTick();
		}

		return AEItemStack.create( remaining );
	}

	private ItemStack performInject( ItemStack remaining )
	{
		// For actually inserting the stack, we try to fill up existing stacks in the inventory, and then move onto free slots
		// Think about this: In a storage drawer or barrel setup, we'd want to attempt to fill up containers that already have the
		// item we're trying to insert first. The second phase should not cost a considerable amount of time, since it will just search
		// for empty slots.
		int slotCount = itemHandler.getSlots();

		// This array is used to remember which slots were viable but skipped in the first phase
		// This avoids calling getStackInSlot for each slot a second time. Hopefully the JVM will decide
		// to allocate this array on the stack
		boolean[] retry = new boolean[slotCount];

		for( int i = 0; i < slotCount; i++ )
		{
			ItemStack stackInSlot = itemHandler.getStackInSlot( i );

			if( stackInSlot == null )
			{
				retry[i] = true;
				continue; // In the first phase, we try to top up existing item stacks
			}

			remaining = itemHandler.insertItem( i, remaining, false );
			if( remaining == null )
			{
				return null; // Awesome, full stack consumed
			}
		}

		// If we reached this point, we still have items to insert, our first pass failed to insert
		// all items. Now we try to insert into empty slots.
		for( int i = 0; i < slotCount; i++ )
		{
			if( retry[i] )
			{
				remaining = itemHandler.insertItem( i, remaining, false );
				if( remaining == null )
				{
					break; // Awesome, full stack consumed
				}
			}
		}

		return remaining;
	}

	private ItemStack simulateInject( ItemStack remaining )
	{
		for( int i = 0; i < itemHandler.getSlots(); i++ )
		{
			// We have a chance to use this slot for injection
			remaining = itemHandler.insertItem( i, remaining, true );
			if( remaining == null )
			{
				break; // Awesome, full stack consumed
			}
		}
		return remaining;
	}

	@Override
	public IAEItemStack extractItems( IAEItemStack request, Actionable mode, BaseActionSource src )
	{

		ItemStack req = request.getItemStack();
		int remainingSize = req.stackSize;

		// Use this to gather the requested items
		ItemStack gathered = null;

		final boolean simulate = ( mode == Actionable.SIMULATE );

		for( int i = 0; i < itemHandler.getSlots(); i++ )
		{
			ItemStack sub = itemHandler.getStackInSlot( i );

			if( !Platform.isSameItem( sub, req ) )
			{
				continue;
			}

			ItemStack extracted;

			// We have to loop here because according to the docs, the handler shouldn't return a stack with size > maxSize, even if we
			// request more. So even if it returns a valid stack, it might have more stuff.
			do
			{
				extracted = itemHandler.extractItem( i, remainingSize, simulate );
				if( extracted != null )
				{
					if( extracted.stackSize > remainingSize )
					{
						// Something broke. It should never return more than we requested... We're going to silently eat the remainder
						AELog.warn( "Mod that provided item handler {} is broken. Returned {} items, even though we requested {}.",
								itemHandler.getClass().getSimpleName(), extracted.stackSize, remainingSize );
						extracted.stackSize = remainingSize;
					}

					// We're just gonna use the first stack we get our hands on as the template for the rest
					if( gathered == null )
					{
						gathered = extracted;
					}
					else
					{
						gathered.stackSize += extracted.stackSize;
					}
					remainingSize -= gathered.stackSize;
				}
			}
			while( extracted != null && remainingSize > 0 );

			// Done?
			if( remainingSize <= 0 )
			{
				break;
			}
		}

		if( gathered != null )
		{
			if( mode == Actionable.MODULATE )
			{
				this.onTick();
			}

			return AEItemStack.create( gathered );
		}

		return null;
	}

	@Override
	public TickRateModulation onTick()
	{
		LinkedList<IAEItemStack> changes = new LinkedList<>();

		int slots = itemHandler.getSlots();

		// Make room for new slots
		if( slots > cachedStacks.length )
		{
			cachedStacks = Arrays.copyOf( cachedStacks, slots );
			cachedAeStacks = Arrays.copyOf( cachedAeStacks, slots );
		}

		for( int slot = 0; slot < slots; slot++ )
		{
			// Save the old stuff
			ItemStack oldIS = cachedStacks[slot];
			IAEItemStack oldAeIS = cachedAeStacks[slot];

			ItemStack newIS = itemHandler.getStackInSlot( slot );

			if( this.isDifferent( newIS, oldIS ) )
			{
				addItemChange( slot, oldAeIS, newIS, changes );
			}
			else if( newIS != null && oldIS != null )
			{
				addPossibleStackSizeChange( slot, oldAeIS, newIS, changes );
			}
		}

		// Handle cases where the number of slots actually is lower now than before
		if( slots < cachedStacks.length )
		{
			for( int slot = slots; slot < cachedStacks.length; slot++ )
			{
				IAEItemStack aeStack = cachedAeStacks[slot];
				if( aeStack != null )
				{
					IAEItemStack a = aeStack.copy();
					a.setStackSize( -a.getStackSize() );
					changes.add( a );
				}
			}

			// Reduce the cache size
			cachedStacks = Arrays.copyOf( cachedStacks, slots );
			cachedAeStacks = Arrays.copyOf( cachedAeStacks, slots );
		}

		if( !changes.isEmpty() )
		{
			this.postDifference( changes );
			return TickRateModulation.URGENT;
		}
		else
		{
			return TickRateModulation.SLOWER;
		}
	}

	private void addItemChange( int slot, IAEItemStack oldAeIS, ItemStack newIS, List<IAEItemStack> changes )
	{
		// Completely different item
		cachedStacks[slot] = newIS;
		cachedAeStacks[slot] = AEItemStack.create( newIS );

		// If we had a stack previously in this slot, notify the newtork about its disappearance
		if( oldAeIS != null )
		{
			oldAeIS.setStackSize( -oldAeIS.getStackSize() );
			changes.add( oldAeIS );
		}

		// Notify the network about the new stack. Note that this is null if newIS was null
		if( cachedAeStacks[slot] != null )
		{
			changes.add( cachedAeStacks[slot] );
		}
	}

	private void addPossibleStackSizeChange( int slot, IAEItemStack oldAeIS, ItemStack newIS, List<IAEItemStack> changes )
	{
		// Still the same item, but amount might have changed
		long diff = newIS.stackSize - oldAeIS.getStackSize();

		if( diff != 0 )
		{
			IAEItemStack stack = oldAeIS.copy();
			stack.setStackSize( newIS.stackSize );

			cachedStacks[slot] = newIS;
			cachedAeStacks[slot] = stack;

			final IAEItemStack a = stack.copy();
			a.setStackSize( diff );
			changes.add( a );
		}
	}

	private boolean isDifferent( final ItemStack a, final ItemStack b )
	{
		if( a == b && b == null )
		{
			return false;
		}

		return a == null || b == null || !Platform.isSameItemPrecise( a, b );
	}

	private void postDifference( Iterable<IAEItemStack> a )
	{
		final Iterator<Map.Entry<IMEMonitorHandlerReceiver<IAEItemStack>, Object>> i = this.listeners.entrySet().iterator();
		while( i.hasNext() )
		{
			final Map.Entry<IMEMonitorHandlerReceiver<IAEItemStack>, Object> l = i.next();
			final IMEMonitorHandlerReceiver<IAEItemStack> key = l.getKey();
			if( key.isValid( l.getValue() ) )
			{
				key.postChange( this, a, mySource );
			}
			else
			{
				i.remove();
			}
		}
	}

	@Override
	public void setActionSource( final BaseActionSource mySource )
	{
		this.mySource = mySource;
	}

	@Override
	public IItemList<IAEItemStack> getAvailableItems( IItemList<IAEItemStack> out )
	{

		for( int i = 0; i < itemHandler.getSlots(); i++ )
		{
			out.addStorage( AEItemStack.create( itemHandler.getStackInSlot( i ) ) );
		}

		return out;
	}

	@Override
	public StorageChannel getChannel()
	{
		return StorageChannel.ITEMS;
	}

	@Override
	public void addListener( final IMEMonitorHandlerReceiver<IAEItemStack> l, final Object verificationToken )
	{
		this.listeners.put( l, verificationToken );
	}

	@Override
	public void removeListener( final IMEMonitorHandlerReceiver<IAEItemStack> l )
	{
		this.listeners.remove( l );
	}
}
