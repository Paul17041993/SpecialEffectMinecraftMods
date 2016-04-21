package com.specialeffect.mods;

import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.specialeffect.callbacks.BaseClassWithCallbacks;
import com.specialeffect.callbacks.IOnLiving;
import com.specialeffect.callbacks.OnLivingCallback;
import com.specialeffect.callbacks.SingleShotOnLivingCallback;
import com.specialeffect.messages.MovePlayerMessage;
import com.specialeffect.utils.ModUtils;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

@Mod(modid = MoveWithGaze.MODID, 
	 version = ModUtils.VERSION,
	 name = MoveWithGaze.NAME,
	 guiFactory = "com.specialeffect.gui.GuiFactoryWalkWithGaze")
public class MoveWithGaze extends BaseClassWithCallbacks {
	public static final String MODID = "specialeffect.movewithgaze";
    public static final String NAME = "MoveWithGaze";

    private static KeyBinding mToggleAutoWalkKB;
    
    public static Configuration mConfig;
    private static int mQueueLength = 50;

    private static boolean mMoveWhenMouseStationary = false;
    public static float mCustomSpeedFactor = 0.8f;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {    
    	FMLCommonHandler.instance().bus().register(this);  
    	
    	ModUtils.setupModInfo(event, this.MODID, this.NAME,
				"Add key binding to start/stop walking continuously, with direction controlled by mouse/eyetracker");
    	ModUtils.setAsParent(event, SpecialEffectMovements.MODID);

    	// Set up config
    	mConfig = new Configuration(event.getSuggestedConfigurationFile());
    	this.syncConfig();
    }
    
    
    @SubscribeEvent
	public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent eventArgs) {
		if(eventArgs.modID.equals(this.MODID)) {
			syncConfig();
		}
	}
	
	public static void syncConfig() {
        mQueueLength = mConfig.getInt("Smoothness filter", Configuration.CATEGORY_GENERAL, mQueueLength, 
				1, 200, "How many ticks to take into account for slowing down while looking around. (smaller number = faster)");
        mMoveWhenMouseStationary = mConfig.getBoolean("Move when mouse stationary", Configuration.CATEGORY_GENERAL, 
        									mMoveWhenMouseStationary, "Continue walking forward when the mouse is stationary. Recommended to be turned off for eye gaze control.");
        mCustomSpeedFactor = mConfig.getFloat("Speed factor", Configuration.CATEGORY_GENERAL, mCustomSpeedFactor, 0.0f, 1.0f, 
        						"A scaling factor for speed. 1.0 = maximum."); 
        if (mConfig.hasChanged()) {
        	mConfig.save();
        }
	}
	
    @EventHandler
    public void init(FMLInitializationEvent event)
    {
		// Subscribe to event buses
        FMLCommonHandler.instance().bus().register(this);
    	MinecraftForge.EVENT_BUS.register(this);    	

    	// Register key bindings	
    	mToggleAutoWalkKB = new KeyBinding("Toggle auto-walk", Keyboard.KEY_H, "SpecialEffect");
        ClientRegistry.registerKeyBinding(mToggleAutoWalkKB);
        
        mPrevLookDirs = new LinkedBlockingQueue<Vec3>();
        
		// Register an icon for the overlay
		mIconIndex = StateOverlay.registerTextureLeft("specialeffect:icons/walk.png");
    }
    
    private static int mIconIndex;
    
    @SubscribeEvent
    public void onLiving(LivingUpdateEvent event) {
    	if(event.entityLiving instanceof EntityPlayer) {

    		EntityPlayer player = (EntityPlayer)event.entityLiving;    		
    		
       		// Add current look dir to queue
    		mPrevLookDirs.add(player.getLookVec());
       		while (mPrevLookDirs.size() > mQueueLength) {
       			mPrevLookDirs.remove();
       		}
       		
       		// Explanation of strategy:
       		// - when turning a corner, we want to slow down to make it a bit more manageable.
       		// - if it takes time to turn the auto-walk function off (e.g. using an eye gaze with dwell click) then
       		//   you don't want to continue walking. In this case you can opt to not walk on any ticks where the mouse
       		//   hasn't moved at all. This is mainly applicable to gaze input.
       		// - If walking into a wall, don't keep walking fast!
            if (mDoingAutoWalk && (mMoveWhenMouseStationary || MouseHandler.hasPendingEvent()) ) {
            	
            	double forward = (double)mCustomSpeedFactor; 
            	
            	// Slow down when you've been turning a corner
            	double slowDownCorners= slowdownFactorViewDirs();
            	
            	// Slow down when you've got a wall in front of you
            	// TODO: Rethink this. It wasn't working so well
            	//double slowDownWalls = slowdownFactorWall(player);
            	
            	// Slow down when you're looking really far up/down
            	double slowDownPitch = slowdownFactorPitch(player);

            	if (!player.isOnLadder()) {
            		forward *= Math.min(slowDownCorners, slowDownPitch);
            	}	
            	
            	// Adjust according to FPS (to get some consistency across installations)
            	forward *= fpsFactor();
            	
            	if (player.isRiding()) {
					// Ask server to move entity being ridden
					WalkIncrements.network.sendToServer(
						new MovePlayerMessage((float)forward, 0.0f));
				}
				else {
					player.moveEntityWithHeading(0.0f, (float)forward);
				}            
            }
            
			this.processQueuedCallbacks(event);
			
    	}
    }
    
    private double slowdownFactorPitch(EntityPlayer player) {
    	float f = player.rotationPitch;
    	if (f < -60 || f > 60) {
    		return 0.0f;
    	}
    	else if (f < -30 || f > 40) {
    		return 0.5f;
    	}
    	else {
    		return 1.0f;
    	}
	}

	private double fpsFactor() {
		int currFps = Minecraft.getDebugFPS();
		int standardFps = 30; // what we tune on
		return Math.min(1.0, (double)standardFps/(double)currFps);
	}

	private boolean isDirectlyFacingSideHit(EnumFacing sideHit, Vec3 lookVec) {
    	double thresh = 0.8;
    	switch(sideHit) {
		case NORTH:
			if (lookVec.zCoord > thresh){
				return true;
			}
			break;
		case EAST:
			if (lookVec.xCoord < -thresh){
				return true;
			}
			break;
		case SOUTH:
			if (lookVec.zCoord < -thresh){
				return true;
			}
			break;
		case WEST:
			if (lookVec.xCoord > thresh){
				return true;
			}
			break;
		default:
			break;
    	}
    	return false;
    }
    
    // Check if there's a block at the given position which
    // blocks movement.
    private boolean doesBlockMovement(BlockPos pos) {
    	World world = Minecraft.getMinecraft().theWorld;
		Block block = world.getBlockState(pos).getBlock();
		return block.getMaterial().blocksMovement();
    }
    
    private boolean isPlayerDirectlyFacingBlock(EntityPlayer player) {
		Vec3 lookVec = player.getLookVec();
		Vec3 posVec = player.getPositionVector();
		MovingObjectPosition movPos = player.rayTrace(1.0, 1.0f);
		if (null != movPos) { 
			return isDirectlyFacingSideHit(movPos.sideHit, lookVec);
		}
    	return false;
    }
    
    private double slowdownFactorWall(EntityPlayer player) {
    	BlockPos playerPos = player.getPosition();
		Vec3 lookVec = player.getLookVec();
		Vec3 posVec = player.getPositionVector();
		
		// Check block in front of player, and the one above it.
		// Also same two blocks in front.
		BlockPos posInFront = new BlockPos(posVec.xCoord + lookVec.xCoord,
				posVec.yCoord, posVec.zCoord + lookVec.zCoord);
		
		//isPlayerDirectlyFacingBlock(player, posInFront);
		
		BlockPos posInFrontAbove = new BlockPos(posVec.xCoord + lookVec.xCoord,
				posVec.yCoord+1, posVec.zCoord + lookVec.zCoord);
		
		BlockPos posInFrontTwo = new BlockPos(posVec.xCoord + 2*lookVec.xCoord,
				posVec.yCoord, posVec.zCoord + lookVec.zCoord);
		
		BlockPos posInFrontTwoAbove = new BlockPos(posVec.xCoord + 2*lookVec.xCoord,
				posVec.yCoord+1, posVec.zCoord + lookVec.zCoord);

		if (doesBlockMovement(posInFront) &&
				doesBlockMovement(posInFrontAbove)) {
			// If there's a ladder, keep going!
			if (isLadder(posInFront)) {
				return 1.0f;
			}
			// If you're *facing* the wall, then don't keep walking.
			if (isPlayerDirectlyFacingBlock(player)) {
				return 0.0f;
			}
			else {
				// If looking obliquely, slow down a little
				return 0.55f;
			}
		}
		else {
			// If 1 block away from wall, start slowing
			if (doesBlockMovement(posInFrontTwo) &&
					doesBlockMovement(posInFrontTwoAbove)) {
				return 0.5;
			}
			else {
				//default
				return 1.0;
			}
		}
    }

	private boolean isLadder(BlockPos pos) {
		World world = Minecraft.getMinecraft().theWorld;
		Block block = world.getBlockState(pos).getBlock();		
		return ( block != null && block instanceof BlockLadder);
	}

	private double slowdownFactorViewDirs() {
    	// Scale forward-distance by the normal congruency of the last X view-dirs.
    	// We use normal congruency over several ticks to:
    	// - smooth out noise, and
    	// - smooth out effect over time (e.g. keep slow-ish for a couple of ticks after movement).
    	double scalarLength = mPrevLookDirs.size();
    	Vec3 vectorSum = new Vec3(0, 0, 0);
    	
    	// TODO: Sums could be done incrementally rather than looping over everything each time.
    	Iterator<Vec3> iter = mPrevLookDirs.iterator();
    	while (iter.hasNext()) {
            vectorSum = vectorSum.add(iter.next());
    	}
    	double vectorLength = vectorSum.lengthVector();            	
    	double normalCongruency = vectorLength/scalarLength;
    	
    	// If in auto-walk mode, walk forward an amount scaled by the view change (less if looking around)
    	double thresh = 0.9; // below this, no movement
    	double slowdownFactor = Math.max(0, (normalCongruency - thresh)/(1.0-thresh));
    	return slowdownFactor;
	}

	private static boolean mDoingAutoWalk = false;
    private double mWalkDistance = 1.0f;
    private Queue<Vec3> mPrevLookDirs;
    
    public static void stop() {
    	mDoingAutoWalk = false;
		StateOverlay.setStateLeftIcon(mIconIndex, mDoingAutoWalk);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        
        if(mToggleAutoWalkKB.isPressed()) {
        	mDoingAutoWalk = !mDoingAutoWalk;
        	if (mDoingAutoWalk) { 
        		MoveWithGaze2.stop();
        	}
			StateOverlay.setStateLeftIcon(mIconIndex, mDoingAutoWalk);
        	this.queueChatMessage("Auto walk: " + (mDoingAutoWalk ? "ON" : "OFF"));
        }
    }
    
}


