package minecraftbyexample.mbe31_inventory_furnace;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.IntArrayNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SUpdateTileEntityPacket;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.FurnaceTileEntity;
import net.minecraft.util.IIntArray;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.LightType;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * User: brandon3055
 * Date: 06/01/2015
 *
 * TileInventorySmelting is an advanced sided inventory that works like a vanilla furnace except that it has 5 input and output slots,
 * 4 fuel slots and cooks at up to four times the speed.
 * The input slots are used sequentially rather than in parallel, i.e. the first slot cooks, then the second, then the third, etc
 * The fuel slots are used in parallel.  The more slots burning in parallel, the faster the cook time.
 * The code is heavily based on TileEntityFurnace.
 */
public class TileInventoryFurnace extends TileEntity implements ITickable {
	// Create and initialize the itemStacks variable that will store store the itemStacks
	public static final int FUEL_SLOTS_COUNT = 4;
	public static final int INPUT_SLOTS_COUNT = 5;
	public static final int OUTPUT_SLOTS_COUNT = 5;
	public static final int TOTAL_SLOTS_COUNT = FUEL_SLOTS_COUNT + INPUT_SLOTS_COUNT + OUTPUT_SLOTS_COUNT;

	public static final int FIRST_FUEL_SLOT = 0;
	public static final int FIRST_INPUT_SLOT = FIRST_FUEL_SLOT + FUEL_SLOTS_COUNT;
	public static final int FIRST_OUTPUT_SLOT = FIRST_INPUT_SLOT + INPUT_SLOTS_COUNT;

	/**The number of ticks required to cook an item*/
	private static final short COOK_TIME_FOR_COMPLETION = 200;  // vanilla value is 200 = 10 seconds

	private int cachedNumberOfBurningSlots = -1;

	private ItemStack[] itemStacks;

  private final FurnaceStateData furnaceStateData = new FurnaceStateData();

	public TileInventoryFurnace()
	{
		itemStacks = new ItemStack[TOTAL_SLOTS_COUNT];
		clear();
	}

	/**
	 * Returns the amount of fuel remaining on the currently burning item in the given fuel slot.
	 * @fuelSlot the number of the fuel slot (0..3)
	 * @return fraction remaining, between 0 - 1
	 */
	public double fractionOfFuelRemaining(int fuelSlot)
	{
		if (burnTimeInitialValue[fuelSlot] <= 0 ) return 0;
		double fraction = burnTimeRemaining[fuelSlot] / (double)burnTimeInitialValue[fuelSlot];
		return MathHelper.clamp(fraction, 0.0, 1.0);
	}

	/**
	 * return the remaining burn time of the fuel in the given slot
	 * @param fuelSlot the number of the fuel slot (0..3)
	 * @return seconds remaining
	 */
	public int secondsOfFuelRemaining(int fuelSlot)
	{
		if (burnTimeRemaining[fuelSlot] <= 0 ) return 0;
		return burnTimeRemaining[fuelSlot] / 20; // 20 ticks per second
	}

	/**
	 * Get the number of slots which have fuel burning in them.
	 * @return number of slots with burning fuel, 0 - FUEL_SLOTS_COUNT
	 */
	public int numberOfBurningFuelSlots()
	{
		int burningCount = 0;
		for (int burnTime : burnTimeRemaining) {
			if (burnTime > 0) ++burningCount;
		}
		return burningCount;
	}

	/**
	 * Returns the amount of cook time completed on the currently cooking item.
	 * @return fraction remaining, between 0 - 1
	 */
	public double fractionOfCookTimeComplete()
	{
		double fraction = cookTime / (double)COOK_TIME_FOR_COMPLETION;
		return MathHelper.clamp(fraction, 0.0, 1.0);
	}

	// This method is called every tick to update the tile entity, i.e.
	// - see if the fuel has run out, and if so turn the furnace "off" and slowly uncook the current item (if any)
	// - see if any of the item have finished smelting
	// It runs both on the server and the client.
	@Override
	public void update() {
		// If there is nothing to smelt or there is no room in the output, reset cookTime and return
		if (canSmelt()) {
			int numberOfFuelBurning = burnFuel();

			// If fuel is available, keep cooking the item, otherwise start "uncooking" it at double speed
			if (numberOfFuelBurning > 0) {
				cookTime += numberOfFuelBurning;
			}	else {
				cookTime -= 2;
			}

			if (cookTime < 0) cookTime = 0;

			// If cookTime has reached maxCookTime smelt the item and reset cookTime
			if (cookTime >= COOK_TIME_FOR_COMPLETION) {
				smeltItem();
				cookTime = 0;
			}
		}	else {
			cookTime = 0;
		}

		// when the number of burning slots changes, we need to force the block to re-render, otherwise the change in
		//   state will not be visible.  Likewise, we need to force a lighting recalculation.
		// The block update (for renderer) is only required on client side, but the lighting is required on both, since
		//    the client needs it for rendering and the server needs it for crop growth etc
		int numberBurning = numberOfBurningFuelSlots();
		if (cachedNumberOfBurningSlots != numberBurning) {
			cachedNumberOfBurningSlots = numberBurning;
			if (world.isRemote) {
        BlockState iblockstate = this.world.getBlockState(pos);
        final int FLAGS = 3;  // I'm not sure what these flags do, exactly.
        world.notifyBlockUpdate(pos, iblockstate, iblockstate, FLAGS);
			}
			world.checkLightFor(LightType.BLOCK, pos);
		}
	}

	/**
	 * 	for each fuel slot: decreases the burn time, checks if burnTimeRemainings = 0 and tries to consume a new piece of fuel if one is available
	 * @return the number of fuel slots which are burning
	 */
	private int burnFuel() {
		int burningCount = 0;
		boolean inventoryChanged = false;
		// Iterate over all the fuel slots
		for (int i = 0; i < FUEL_SLOTS_COUNT; i++) {
			int fuelSlotNumber = i + FIRST_FUEL_SLOT;
			if (burnTimeRemaining[i] > 0) {
				--burnTimeRemaining[i];
				++burningCount;
			}
			if (burnTimeRemaining[i] == 0) {
				if (!itemStacks[fuelSlotNumber].isEmpty() && getItemBurnTime(itemStacks[fuelSlotNumber]) > 0) {  // isEmpty()
					// If the stack in this slot is not null and is fuel, set burnTimeRemainings & burnTimeInitialValues to the
					// item's burn time and decrease the stack size
					burnTimeRemaining[i] = burnTimeInitialValue[i] = getItemBurnTime(itemStacks[fuelSlotNumber]);
					itemStacks[fuelSlotNumber].shrink(1);  // decreaseStackSize()
					++burningCount;
					inventoryChanged = true;
				// If the stack size now equals 0 set the slot contents to the item container item. This is for fuel
				// item such as lava buckets so that the bucket is not consumed. If the item dose not have
				// a container item getContainerItem returns null which sets the slot contents to null
					if (itemStacks[fuelSlotNumber].getCount() == 0) {  //getStackSize()
						itemStacks[fuelSlotNumber] = itemStacks[fuelSlotNumber].getItem().getContainerItem(itemStacks[fuelSlotNumber]);
					}
				}
			}
		}
		if (inventoryChanged) markDirty();
		return burningCount;
	}

	/**
	 * Check if any of the input item are smeltable and there is sufficient space in the output slots
	 * @return true if smelting is possible
	 */
	private boolean canSmelt() {return smeltItem(false);}

	/**
	 * Smelt an input item into an output slot, if possible
	 */
	private void smeltItem() {smeltItem(true);}

	/**
	 * checks that there is an item to be smelted in one of the input slots and that there is room for the result in the output slots
	 * If desired, performs the smelt
	 * @param performSmelt if true, perform the smelt.  if false, check whether smelting is possible, but don't change the inventory
	 * @return false if no item can be smelted, true otherwise
	 */
	private boolean smeltItem(boolean performSmelt)
	{
		Integer firstSuitableInputSlot = null;
		Integer firstSuitableOutputSlot = null;
		ItemStack result = ItemStack.EMPTY;  //EMPTY_ITEM

		// finds the first input slot which is smeltable and whose result fits into an output slot (stacking if possible)
		for (int inputSlot = FIRST_INPUT_SLOT; inputSlot < FIRST_INPUT_SLOT + INPUT_SLOTS_COUNT; inputSlot++)	{
			if (!itemStacks[inputSlot].isEmpty()) {  //isEmpty()
				result = getSmeltingResultForItem(itemStacks[inputSlot]);
  			if (!result.isEmpty()) {  //isEmpty()
					// find the first suitable output slot- either empty, or with identical item that has enough space
					for (int outputSlot = FIRST_OUTPUT_SLOT; outputSlot < FIRST_OUTPUT_SLOT + OUTPUT_SLOTS_COUNT; outputSlot++) {
						ItemStack outputStack = itemStacks[outputSlot];
						if (outputStack.isEmpty()) {  //isEmpty()
							firstSuitableInputSlot = inputSlot;
							firstSuitableOutputSlot = outputSlot;
							break;
						}

						if (outputStack.getItem() == result.getItem() && (!outputStack.getHasSubtypes() || outputStack.getMetadata() == outputStack.getMetadata())
										&& ItemStack.areItemStackTagsEqual(outputStack, result)) {
							int combinedSize = itemStacks[outputSlot].getCount() + result.getCount();  //getStackSize()
							if (combinedSize <= getInventoryStackLimit() && combinedSize <= itemStacks[outputSlot].getMaxStackSize()) {
								firstSuitableInputSlot = inputSlot;
								firstSuitableOutputSlot = outputSlot;
								break;
							}
						}
					}
					if (firstSuitableInputSlot != null) break;
				}
			}
		}

		if (firstSuitableInputSlot == null) return false;
		if (!performSmelt) return true;

		// alter input and output
		itemStacks[firstSuitableInputSlot].shrink(1);  // decreaseStackSize()
		if (itemStacks[firstSuitableInputSlot].getCount() <= 0) {
      itemStacks[firstSuitableInputSlot] = ItemStack.EMPTY;  //getStackSize(), EmptyItem
    }
		if (itemStacks[firstSuitableOutputSlot].isEmpty()) {  // isEmpty()
			itemStacks[firstSuitableOutputSlot] = result.copy(); // Use deep .copy() to avoid altering the recipe
		} else {
      int newStackSize = itemStacks[firstSuitableOutputSlot].getCount() + result.getCount();
			itemStacks[firstSuitableOutputSlot].setCount(newStackSize) ;  //setStackSize(), getStackSize()
		}
		markDirty();
		return true;
	}

	// returns the smelting result for the given stack. Returns null if the given stack can not be smelted
	public static ItemStack getSmeltingResultForItem(ItemStack stack) { return FurnaceRecipes.instance().getSmeltingResult(stack); }

	// returns the number of ticks the given item will burn. Returns 0 if the given item is not a valid fuel
	public static short getItemBurnTime(ItemStack stack)
	{
		int burntime = FurnaceTileEntity.getItemBurnTime(stack);  // just use the vanilla values
		return (short)MathHelper.clamp(burntime, 0, Short.MAX_VALUE);
	}

	// Gets the number of slots in the inventory
	@Override
	public int getSizeInventory() {
		return itemStacks.length;
	}

	// returns true if all of the slots in the inventory are empty
	@Override
	public boolean isEmpty()
	{
		for (ItemStack itemstack : itemStacks) {
			if (!itemstack.isEmpty()) {  // isEmpty()
				return false;
			}
		}

		return true;
	}

	// Gets the stack in the given slot
	@Override
	public ItemStack getStackInSlot(int i) {
		return itemStacks[i];
	}

	/**
	 * Removes some of the units from itemstack in the given slot, and returns as a separate itemstack
	 * @param slotIndex the slot number to remove the item from
	 * @param count the number of units to remove
	 * @return a new itemstack containing the units removed from the slot
	 */
	@Override
	public ItemStack decrStackSize(int slotIndex, int count) {
		ItemStack itemStackInSlot = getStackInSlot(slotIndex);
		if (itemStackInSlot.isEmpty()) return ItemStack.EMPTY;  //isEmpty(), EMPTY_ITEM

		ItemStack itemStackRemoved;
		if (itemStackInSlot.getCount() <= count) { //getStackSize
			itemStackRemoved = itemStackInSlot;
			setInventorySlotContents(slotIndex, ItemStack.EMPTY); // EMPTY_ITEM
		} else {
			itemStackRemoved = itemStackInSlot.splitStack(count);
			if (itemStackInSlot.getCount() == 0) { //getStackSize
				setInventorySlotContents(slotIndex, ItemStack.EMPTY); //EMPTY_ITEM
			}
		}
		markDirty();
		return itemStackRemoved;
	}

	// overwrites the stack in the given slotIndex with the given stack
	@Override
	public void setInventorySlotContents(int slotIndex, ItemStack itemstack) {
		itemStacks[slotIndex] = itemstack;
		if (!itemstack.isEmpty() && itemstack.getCount() > getInventoryStackLimit()) {  // isEmpty();  getStackSize()
			itemstack.setCount(getInventoryStackLimit());  //setStackSize()
		}
		markDirty();
	}

	// This is the maximum number if item allowed in each slot
	// This only affects things such as hoppers trying to insert item you need to use the container to enforce this for players
	// inserting item via the gui
	@Override
	public int getInventoryStackLimit() {
		return 64;
	}

	// Return true if the given player is able to use this block. In this case it checks that
	// 1) the world tileentity hasn't been replaced in the meantime, and
	// 2) the player isn't too far away from the centre of the block
	@Override
	public boolean isUsableByPlayer(PlayerEntity player) {
		if (this.world.getTileEntity(this.pos) != this) return false;
		final double X_CENTRE_OFFSET = 0.5;
		final double Y_CENTRE_OFFSET = 0.5;
		final double Z_CENTRE_OFFSET = 0.5;
		final double MAXIMUM_DISTANCE_SQ = 8.0 * 8.0;
		return player.getDistanceSq(pos.getX() + X_CENTRE_OFFSET, pos.getY() + Y_CENTRE_OFFSET, pos.getZ() + Z_CENTRE_OFFSET) < MAXIMUM_DISTANCE_SQ;
	}

	// Return true if the given stack is allowed to be inserted in the given slot
	// Unlike the vanilla furnace, we allow anything to be placed in the fuel slots
	static public boolean isItemValidForFuelSlot(ItemStack itemStack)
	{
		return true;
	}

	// Return true if the given stack is allowed to be inserted in the given slot
	// Unlike the vanilla furnace, we allow anything to be placed in the fuel slots
	static public boolean isItemValidForInputSlot(ItemStack itemStack)
	{
		return true;
	}

	// Return true if the given stack is allowed to be inserted in the given slot
	// Unlike the vanilla furnace, we allow anything to be placed in the fuel slots
	static public boolean isItemValidForOutputSlot(ItemStack itemStack)
	{
		return false;
	}

	//------------------------------

	// This is where you save any data that you don't want to lose when the tile entity unloads
	// In this case, it saves the state of the furnace (burn time etc) and the itemstacks stored in the fuel, input, and output slots
	@Override
	public CompoundNBT writeToNBT(CompoundNBT parentNBTTagCompound)
	{
		super.write(parentNBTTagCompound); // The super call is required to save and load the tiles location

    furnaceStateData.putIntoNBT(parentNBTTagCompound);

//		// Save the stored item stacks

		// to use an analogy with Java, this code generates an array of hashmaps
		// The itemStack in each slot is converted to an NBTTagCompound, which is effectively a hashmap of key->value pairs such
		//   as slot=1, id=2353, count=1, etc
		// Each of these NBTTagCompound are then inserted into NBTTagList, which is similar to an array.
		ListNBT dataForAllSlots = new ListNBT();
		for (int i = 0; i < this.itemStacks.length; ++i) {
			if (!this.itemStacks[i].isEmpty()) {  //isEmpty()
				CompoundNBT dataForThisSlot = new CompoundNBT();
				dataForThisSlot.setByte("Slot", (byte) i);
				this.itemStacks[i].writeToNBT(dataForThisSlot);
				dataForAllSlots.appendTag(dataForThisSlot);
			}
		}
    return parentNBTTagCompound;
	}

	// This is where you load the data that you saved in writeToNBT
	@Override
	public void readFromNBT(CompoundNBT nbtTagCompound)
	{
		super.read(nbtTagCompound); // The super call is required to save and load the tiles location

    furnaceStateData.readFromNBT(nbtTagCompound);

    final byte NBT_TYPE_COMPOUND = 10;       // See NBTBase.createNewByType() for a listing
		ListNBT dataForAllSlots = nbtTagCompound.getTagList("Items", NBT_TYPE_COMPOUND);

		Arrays.fill(itemStacks, ItemStack.EMPTY);           // set all slots to empty EMPTY_ITEM
		for (int i = 0; i < dataForAllSlots.tagCount(); ++i) {
			CompoundNBT dataForOneSlot = dataForAllSlots.getCompoundTagAt(i);
			byte slotNumber = dataForOneSlot.getByte("Slot");
			if (slotNumber >= 0 && slotNumber < this.itemStacks.length) {
				this.itemStacks[slotNumber] = new ItemStack(dataForOneSlot);
			}
		}

		// Load everything else.  Trim the arrays (or pad with 0) to make sure they have the correct number of elements
		cookTime = nbtTagCompound.getShort("CookTime");
		burnTimeRemaining = Arrays.copyOf(nbtTagCompound.getIntArray("burnTimeRemainings"), FUEL_SLOTS_COUNT);
		burnTimeInitialValue = Arrays.copyOf(nbtTagCompound.getIntArray("burnTimeInitial"), FUEL_SLOTS_COUNT);
		cachedNumberOfBurningSlots = -1;
	}

//	// When the world loads from disk, the server needs to send the TileEntity information to the client
//	//  it uses getUpdatePacket(), getUpdateTag(), onDataPacket(), and handleUpdateTag() to do this
  @Override
  @Nullable
  public SUpdateTileEntityPacket getUpdatePacket()
  {
    CompoundNBT updateTagDescribingTileEntityState = getUpdateTag();
    final int METADATA = 0;
    return new SUpdateTileEntityPacket(this.pos, METADATA, updateTagDescribingTileEntityState);
  }

  @Override
  public void onDataPacket(NetworkManager net, SUpdateTileEntityPacket pkt) {
    CompoundNBT updateTagDescribingTileEntityState = pkt.getNbtCompound();
    handleUpdateTag(updateTagDescribingTileEntityState);
  }

  /* Creates a tag containing the TileEntity information, used by vanilla to transmit from server to client
     Warning - although our getUpdatePacket() uses this method, vanilla also calls it directly, so don't remove it.
   */
  @Override
  public CompoundNBT getUpdateTag()
  {
		CompoundNBT nbtTagCompound = new CompoundNBT();
		writeToNBT(nbtTagCompound);
    return nbtTagCompound;
  }

  /* Populates this TileEntity with information from the tag, used by vanilla to transmit from server to client
   Warning - although our onDataPacket() uses this method, vanilla also calls it directly, so don't remove it.
 */
  @Override
  public void handleUpdateTag(CompoundNBT tag)
  {
    this.readFromNBT(tag);
  }
  //------------------------

	// set all slots to empty
	@Override
	public void clear() {
		Arrays.fill(itemStacks, ItemStack.EMPTY);  //EMPTY_ITEM
	}

	// will add a key for this container to the lang file so we can name it in the GUI
	@Override
	public String getName() {
		return "container.mbe31_inventory_furnace.name";
	}

	@Override
	public boolean hasCustomName() {
		return false;
	}

	// standard code to look up what the human-readable name is
  @Nullable
  @Override
  public ITextComponent getDisplayName() {
		return this.hasCustomName() ? new StringTextComponent(this.getName()) : new TranslationTextComponent(this.getName());
	}

	// Fields are used to send non-inventory information from the server to interested clients
	// The container code caches the fields and sends the client any fields which have changed.
	// The field ID is limited to byte, and the field value is limited to short. (if you use more than this, they get cast down
	//   in the network packets)
	// If you need more than this, or shorts are too small, use a custom packet in your container instead.

	private static final byte COOK_FIELD_ID = 0;
	private static final byte FIRST_BURN_TIME_REMAINING_FIELD_ID = 1;
	private static final byte FIRST_BURN_TIME_INITIAL_FIELD_ID = FIRST_BURN_TIME_REMAINING_FIELD_ID + (byte)FUEL_SLOTS_COUNT;
	private static final byte NUMBER_OF_FIELDS = FIRST_BURN_TIME_INITIAL_FIELD_ID + (byte)FUEL_SLOTS_COUNT;

	@Override
	public int getField(int id) {
		if (id == COOK_FIELD_ID) return cookTime;
		if (id >= FIRST_BURN_TIME_REMAINING_FIELD_ID && id < FIRST_BURN_TIME_REMAINING_FIELD_ID + FUEL_SLOTS_COUNT) {
			return burnTimeRemaining[id - FIRST_BURN_TIME_REMAINING_FIELD_ID];
		}
		if (id >= FIRST_BURN_TIME_INITIAL_FIELD_ID && id < FIRST_BURN_TIME_INITIAL_FIELD_ID + FUEL_SLOTS_COUNT) {
			return burnTimeInitialValue[id - FIRST_BURN_TIME_INITIAL_FIELD_ID];
		}
		System.err.println("Invalid field ID in TileInventorySmelting.getField:" + id);
		return 0;
	}

	@Override
	public void setField(int id, int value)
	{
		if (id == COOK_FIELD_ID) {
			cookTime = (short)value;
		} else if (id >= FIRST_BURN_TIME_REMAINING_FIELD_ID && id < FIRST_BURN_TIME_REMAINING_FIELD_ID + FUEL_SLOTS_COUNT) {
			burnTimeRemaining[id - FIRST_BURN_TIME_REMAINING_FIELD_ID] = value;
		} else if (id >= FIRST_BURN_TIME_INITIAL_FIELD_ID && id < FIRST_BURN_TIME_INITIAL_FIELD_ID + FUEL_SLOTS_COUNT) {
			burnTimeInitialValue[id - FIRST_BURN_TIME_INITIAL_FIELD_ID] = value;
		} else {
			System.err.println("Invalid field ID in TileInventorySmelting.setField:" + id);
		}
	}

	@Override
	public int getFieldCount() {
		return NUMBER_OF_FIELDS;
	}

	// -----------------------------------------------------------------------------------------------------------
	// The following methods are not needed for this example but are part of IInventory so they must be implemented

	// Unused unless your container specifically uses it.
	// Return true if the given stack is allowed to go in the given slot
	@Override
	public boolean isItemValidForSlot(int slotIndex, ItemStack itemstack) {
		return false;
	}

	/**
	 * This method removes the entire contents of the given slot and returns it.
	 * Used by containers such as crafting tables which return any item in their slots when you close the GUI
	 * @param slotIndex
	 * @return
	 */
	@Override
	public ItemStack removeStackFromSlot(int slotIndex) {
		ItemStack itemStack = getStackInSlot(slotIndex);
		if (!itemStack.isEmpty()) setInventorySlotContents(slotIndex, ItemStack.EMPTY);  //isEmpty();  EMPTY_ITEM
		return itemStack;
	}

	@Override
	public void openInventory(PlayerEntity player) {}

	@Override
	public void closeInventory(PlayerEntity player) {}






  /**
   * ContainerLink is used by the server container to read and write the furnace state data in the TileEntity and to
   *   synchronise it to the client container
   *
   */
  static class ContainerLink implements IIntArray {

    get

    @Override
    public int get(int index) {
      if (index <)
      return allValues[index];
    }

    @Override
    public void set(int index, int value) {
      allValues[index] = value;
    }

    @Override
    public int size() {
      return ;
    }
  }



//  private int burnTime;
//  private int recipesUsed;
//  private int cookTime;
//  private int cookTimeTotal;


}
