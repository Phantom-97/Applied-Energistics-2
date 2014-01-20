package appeng.parts.p2p;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Icon;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;
import appeng.me.GridAccessException;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class PartP2PLiquids extends PartP2PTunnel<PartP2PLiquids> implements IFluidHandler
{

	public PartP2PLiquids(ItemStack is) {
		super( is );
	}

	private FluidTankInfo[] getTank()
	{
		if ( output )
		{
			PartP2PLiquids tun = getInput();
			if ( tun != null )
				return new FluidTankInfo[] { new FluidTankInfo( null, 10000 ) };
		}
		else
		{
			try
			{
				if ( !getOutputs().isEmpty() )
					return new FluidTankInfo[] { new FluidTankInfo( null, 10000 ) };
			}
			catch (GridAccessException e)
			{
				// :(
			}
		}
		return new FluidTankInfo[] { new FluidTankInfo( null, 0 ) };
	}

	IFluidHandler cachedTank;

	public float getPowerDrainPerTick()
	{
		return 2.0f;
	};

	private int tmpUsed;

	@Override
	public void writeToNBT(NBTTagCompound tag)
	{
		super.writeToNBT( tag );
	}

	@Override
	public void readFromNBT(NBTTagCompound tag)
	{
		super.readFromNBT( tag );
	}

	@SideOnly(Side.CLIENT)
	public Icon getTypeTexture()
	{
		return Block.blockLapis.getBlockTextureFromSide( 0 );
	}

	List<PartP2PLiquids> getOutputs(Fluid input)
	{
		List<PartP2PLiquids> outs = new LinkedList<PartP2PLiquids>();

		try
		{
			for (PartP2PLiquids l : getOutputs())
			{
				IFluidHandler targ = getTarget();
				if ( targ != null )
				{
					if ( targ.canFill( side.getOpposite(), input ) )
						outs.add( l );
				}
			}
		}
		catch (GridAccessException e)
		{
			// :P
		}

		return outs;
	}

	public void onNeighborBlockChange()
	{
		cachedTank = null;
	};

	IFluidHandler getTarget()
	{
		if ( cachedTank != null )
			return cachedTank;

		TileEntity te = tile.worldObj.getBlockTileEntity( tile.xCoord + side.offsetX, tile.yCoord + side.offsetY, tile.zCoord + side.offsetZ );
		if ( te instanceof IFluidHandler )
			return cachedTank = (IFluidHandler) te;

		return null;
	}

	@Override
	public int fill(ForgeDirection from, FluidStack resource, boolean doFill)
	{
		List<PartP2PLiquids> list = getOutputs( resource.getFluid() );
		int requestTotal = 0;

		Iterator<PartP2PLiquids> i = list.iterator();
		while (i.hasNext())
		{
			PartP2PLiquids l = i.next();
			IFluidHandler tank = l.getTarget();
			if ( tank != null )
				l.tmpUsed = tank.fill( l.side.getOpposite(), resource.copy(), false );
			else
				l.tmpUsed = 0;

			if ( l.tmpUsed <= 0 )
				i.remove();
			else
				requestTotal += l.tmpUsed;
		}

		if ( requestTotal <= 0 )
			return 0;

		if ( !doFill )
			return Math.min( resource.amount, requestTotal );

		int avilable = resource.amount;
		int used = 0;

		i = list.iterator();
		while (i.hasNext())
		{
			PartP2PLiquids l = i.next();

			FluidStack insert = resource.copy();
			insert.amount = (int) Math.ceil( insert.amount * ((double) l.tmpUsed / (double) requestTotal) );
			if ( insert.amount > avilable )
				insert.amount = avilable;

			IFluidHandler tank = l.getTarget();
			if ( tank != null )
				l.tmpUsed = tank.fill( l.side.getOpposite(), insert.copy(), true );
			else
				l.tmpUsed = 0;

			avilable -= insert.amount;
			used += insert.amount;
		}

		return used;
	}

	@Override
	public boolean canFill(ForgeDirection from, Fluid fluid)
	{
		return !output && from.equals( side ) && !getOutputs( fluid ).isEmpty();
	}

	@Override
	public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain)
	{
		return null;
	}

	@Override
	public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain)
	{
		return null;
	}

	@Override
	public boolean canDrain(ForgeDirection from, Fluid fluid)
	{
		return false;
	}

	@Override
	public FluidTankInfo[] getTankInfo(ForgeDirection from)
	{
		if ( from.equals( side ) )
			return getTank();
		return new FluidTankInfo[0];
	}

}