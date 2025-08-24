package com.g2806.undertaleextinct;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Undertale gun attack overlay with 4 sliders attack system
 */
public class UndertaleAttackGunOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("UndertaleAttackGunOverlay");
    private static final String MOD_ID = "undertaleextinct";
    
    // Texture identifiers
    private static final Identifier ATTACK_FRAME_TEXTURE = new Identifier(MOD_ID, "textures/gui/attack_frame.png");
    private static final Identifier ATTACK_SLIDER_TEXTURE = new Identifier(MOD_ID, "textures/gui/attack_slider.png");
    
    // Slash animation textures (1.png to 5.png)
    private static final int SLASH_FRAMES = 5;
    private static final List<Identifier> SLASH_TEXTURES = new ArrayList<>();
    
    static {
        // Initialize slash texture identifiers
        for (int i = 1; i <= SLASH_FRAMES; i++) {
            SLASH_TEXTURES.add(new Identifier(MOD_ID, "textures/gui/slash/" + i + ".png"));
        }
    }
    
    // Overlay state
    private boolean isActive = false;
    private boolean texturesLoaded = false;
    private boolean sliderTextureLoaded = false;
    private boolean slashTexturesLoaded = false;
    
    // Frame dimensions (smaller and thicker for better positioning)
    private static final int FRAME_WIDTH = 541;
    private static final int FRAME_HEIGHT = 107;
    
    // Slider dimensions (made thinner)
    private static final int SLIDER_WIDTH = 4;
    private static final int SLIDER_HEIGHT = 120;
    
    // 4 Slider system (spawning with 1 second delay, click affects current slider)
    private static final int NUM_SLIDERS = 4;
    private float[] sliderPositions = new float[NUM_SLIDERS]; // 0.0 = left, 1.0 = right
    private boolean[] sliderMoving = new boolean[NUM_SLIDERS]; // true = moving, false = stopped
    private boolean[] sliderFinished = new boolean[NUM_SLIDERS]; // true = clicked or reached end
    private boolean[] sliderSpawned = new boolean[NUM_SLIDERS]; // true = slider has spawned
    private int[] attackValues = new int[NUM_SLIDERS]; // 0-100 value for each slider
    private int currentSlider = 0; // Currently active slider (0-3)
    private boolean allSlidersFinished = false;
    private boolean showResult = false;
    private int totalAttackValue = 0; // Sum of all 4 slider values
    private long[] sliderSpawnTimes = new long[NUM_SLIDERS]; // When each slider should spawn
    private long attackStartTime = 0;
    private boolean wasMousePressed = false; // Track mouse state to prevent multiple clicks
    
    // Slider movement settings
    private static final float SLIDER_SPEED = 0.02f;
    private static final long SLIDER_SPAWN_DELAY = 2000; // 2 seconds in milliseconds
    
    // Slash animation state
    private boolean playingSlashAnimation = false;
    private int currentSlashFrame = 0;
    private int slashAnimationTicks = 0;
    private static final int SLASH_FRAME_DELAY = 3; // Ticks between frames
    
    public UndertaleAttackGunOverlay() {
        // Initialize all sliders
        resetSliders();
        
        // Load textures on initialization
        loadTextures();
        
        // Register tick event for slider movement, animation updates, and click detection
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isActive && !allSlidersFinished) {
                updateCurrentSlider();
                checkForClick(client);
            }
            updateSlashAnimation();
        });
        
        // Register render callback
        HudRenderCallback.EVENT.register(this::renderOverlay);
        
        LOGGER.info("UndertaleAttackGunOverlay initialized");
    }
    
    private void resetSliders() {
        attackStartTime = System.currentTimeMillis();
        
        for (int i = 0; i < NUM_SLIDERS; i++) {
            sliderPositions[i] = 0.0f;
            sliderMoving[i] = false;
            sliderFinished[i] = false;
            sliderSpawned[i] = false;
            attackValues[i] = 0;
            // Set spawn times: first at start time, others with 1 second delays
            sliderSpawnTimes[i] = attackStartTime + (i * SLIDER_SPAWN_DELAY);
        }
        
        currentSlider = 0;
        allSlidersFinished = false;
        showResult = false;
        totalAttackValue = 0;
        wasMousePressed = false;
        
        // Start first slider immediately
        sliderSpawned[0] = true;
        sliderMoving[0] = true;
    }
    
    private void updateCurrentSlider() {
        if (allSlidersFinished) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // Check for new sliders to spawn (only via timer, not clicks)
        for (int i = 0; i < NUM_SLIDERS; i++) {
            if (!sliderSpawned[i] && currentTime >= sliderSpawnTimes[i]) {
                sliderSpawned[i] = true;
                sliderMoving[i] = true;
                currentSlider = i;
            }
        }
        
        // Update all moving sliders
        for (int i = 0; i < NUM_SLIDERS; i++) {
            if (sliderMoving[i] && !sliderFinished[i] && sliderSpawned[i]) {
                sliderPositions[i] += SLIDER_SPEED;
                
                // Check if slider reached the end
                if (sliderPositions[i] >= 1.0f) {
                    sliderPositions[i] = 1.0f;
                    sliderMoving[i] = false;
                    sliderFinished[i] = true;
                    
                    // Calculate attack value for this slider
                    calculateSliderValue(i);
                    
                    // Move focus to next slider if this one reached the end
                    if (i == currentSlider) {
                        currentSlider++;
                    }
                }
            }
        }
        
        // Check if all sliders are finished
        boolean allFinished = true;
        for (int i = 0; i < NUM_SLIDERS; i++) {
            if (!sliderSpawned[i] || (!sliderFinished[i] && sliderMoving[i])) {
                allFinished = false;
                break;
            }
        }
        
        if (allFinished) {
            finishAllSliders();
        }
    }
    
    
    private void calculateSliderValue(int sliderIndex) {
        // Convert slider position (0.0-1.0) to attack value (0-100)
        // Position 50 (middle) = Score 100 (best), Position 0/100 (edges) = Score 0 (worst)
        
        float positionValue = sliderPositions[sliderIndex] * 100; // 0.0 -> 0, 0.5 -> 50, 1.0 -> 100
        float distanceFrom50 = Math.abs(positionValue - 50.0f); // 0 at center, 50 at edges
        attackValues[sliderIndex] = Math.round(100 - (distanceFrom50 * 2)); // Linear scoring
        attackValues[sliderIndex] = Math.max(0, Math.min(100, attackValues[sliderIndex])); // Clamp to 0-100
        
    }
    
    
    private void finishAllSliders() {
        allSlidersFinished = true;
        
        // Calculate total attack value (sum of all 4 sliders)
        totalAttackValue = 0;
        for (int value : attackValues) {
            totalAttackValue += value;
        }
        
        // Start slash animation if any attack was successful
        if (totalAttackValue > 0) {
            startSlashAnimation();
        }
        
        showResult = true;
        
        // Send gun attack value to server for scoreboard
        sendGunAttackValueToServer(totalAttackValue);
        
        // Close overlay after appropriate delay
        new Thread(() -> {
            try {
                if (totalAttackValue > 0) {
                    // Wait for slash animation to complete (5 frames * 3 ticks * 50ms per tick = ~0.75 seconds)
                    Thread.sleep(1500); // Extra buffer time
                } else {
                    // No animation, close faster for complete miss
                    Thread.sleep(1000);
                }
                stopGunAttack();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    private void startSlashAnimation() {
        if (!slashTexturesLoaded) return;
        
        playingSlashAnimation = true;
        currentSlashFrame = 0;
        slashAnimationTicks = 0;
    }
    
    private void updateSlashAnimation() {
        if (!playingSlashAnimation) return;
        
        slashAnimationTicks++;
        
        if (slashAnimationTicks >= SLASH_FRAME_DELAY) {
            slashAnimationTicks = 0;
            currentSlashFrame++;
            
            if (currentSlashFrame >= SLASH_FRAMES) {
                playingSlashAnimation = false;
                currentSlashFrame = 0;
            }
        }
    }
    
    private void loadTextures() {
        MinecraftClient client = MinecraftClient.getInstance();
        
        try {
            // Load attack frame texture
            client.getResourceManager().getResource(ATTACK_FRAME_TEXTURE);
            
            // Load slider texture
            client.getResourceManager().getResource(ATTACK_SLIDER_TEXTURE);
            sliderTextureLoaded = true;
            
            texturesLoaded = true;
            LOGGER.info("Gun attack textures loaded successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to load gun attack textures: {}", e.getMessage());
            texturesLoaded = false;
        }
        
        // Load slash animation textures
        int loadedSlashTextures = 0;
        for (Identifier slashTexture : SLASH_TEXTURES) {
            try {
                client.getResourceManager().getResource(slashTexture);
                loadedSlashTextures++;
            } catch (Exception e) {
                LOGGER.warn("Failed to load slash texture {}: {}", slashTexture, e.getMessage());
            }
        }
        
        if (loadedSlashTextures == SLASH_FRAMES) {
            slashTexturesLoaded = true;
            if (slashTexturesLoaded) {
                LOGGER.info("All {} slash animation textures loaded successfully", SLASH_FRAMES);
            } else {
                LOGGER.info("Some slash animation textures missing, animation will be skipped");
            }
        }
    }
    
    public void startGunAttack() {
        if (isActive) {
            LOGGER.info("Gun attack already active, ignoring request");
            return;
        }
        
        LOGGER.info("Starting gun attack overlay");
        isActive = true;
        resetSliders();
        
        if (!texturesLoaded) {
            loadTextures();
        }
    }
    
    public void stopGunAttack() {
        LOGGER.info("Stopping gun attack overlay");
        isActive = false;
        resetSliders();
        playingSlashAnimation = false;
    }
    
    public boolean isActive() {
        return isActive;
    }
    
    public boolean handleClick() {
        if (!isActive || allSlidersFinished) {
            return false;
        }
        
        // Stop the currently active slider only
        if (currentSlider < NUM_SLIDERS && sliderSpawned[currentSlider] && sliderMoving[currentSlider] && !sliderFinished[currentSlider]) {
            sliderMoving[currentSlider] = false;
            sliderFinished[currentSlider] = true;
            calculateSliderValue(currentSlider);
            
            // Move focus to next active slider (but don't spawn it early)
            currentSlider++;
            
            return true;
        }
        
        return false;
    }
    
    private void checkForClick(MinecraftClient client) {
        // Detect single click (press, not hold) using mouse state tracking
        boolean isMousePressed = client.options.attackKey.isPressed();
        
        // Only register click on press (not while holding)
        if (isMousePressed && !wasMousePressed) {
            handleClick();
        }
        
        // Update mouse state for next frame
        wasMousePressed = isMousePressed;
    }
    
    private void sendGunAttackValueToServer(int gunAttackValue) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(gunAttackValue);
        
        ClientPlayNetworking.send(new Identifier(MOD_ID, "gun_attack_value"), buf);
    }
    
    private void renderOverlay(DrawContext context, float tickDelta) {
        if (!isActive || !texturesLoaded) {
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        
        // Render attack frames (4 frames vertically stacked)
        renderGunAttackFrames(context, screenWidth, screenHeight);
        
        // Render sliders
        renderSliders(context, screenWidth, screenHeight);
        
        // Render result if finished
        if (showResult) {
            renderResult(context, screenWidth, screenHeight);
        }
        
        // Render slash animation if playing
        if (playingSlashAnimation) {
            renderSlashAnimation(context, screenWidth, screenHeight);
        }
    }
    
    private void renderGunAttackFrames(DrawContext context, int screenWidth, int screenHeight) {
        // Position single frame near hotbar (same as regular attack)
        int frameX = (screenWidth - FRAME_WIDTH) / 2;
        int frameY = screenHeight - FRAME_HEIGHT - 80; // Same positioning as regular attack
        
        // Draw attack frame background with transparency
        int color = 0x60FFFFFF;
        context.fill(frameX, frameY, frameX + FRAME_WIDTH, frameY + FRAME_HEIGHT, color);
        
        // Draw frame texture
        context.drawTexture(ATTACK_FRAME_TEXTURE, frameX, frameY, 0, 0, FRAME_WIDTH, FRAME_HEIGHT, FRAME_WIDTH, FRAME_HEIGHT);
    }
    
    private void renderSliders(DrawContext context, int screenWidth, int screenHeight) {
        if (!sliderTextureLoaded) return;
        
        int frameX = (screenWidth - FRAME_WIDTH) / 2;
        int frameY = screenHeight - FRAME_HEIGHT - 80;
        
        for (int i = 0; i < NUM_SLIDERS; i++) {
            // Only render sliders that have spawned
            if (!sliderSpawned[i]) continue;
            
            // Calculate slider position within the frame (same as regular attack slider)
            int sliderX = frameX + (int)(sliderPositions[i] * (FRAME_WIDTH - SLIDER_WIDTH));
            int sliderY = frameY + (FRAME_HEIGHT - SLIDER_HEIGHT) / 2 + (i * 3); // 3 pixel offset between sliders
            
            // Draw slider using attack_slider.png texture
            context.drawTexture(ATTACK_SLIDER_TEXTURE, sliderX, sliderY, 0, 0, SLIDER_WIDTH, SLIDER_HEIGHT, SLIDER_WIDTH, SLIDER_HEIGHT);
        }
    }
    
    private void renderResult(DrawContext context, int screenWidth, int screenHeight) {
        String resultText = "Gun Total: " + totalAttackValue + "/400";
        
        // Show individual slider results
        StringBuilder detailText = new StringBuilder("Hits: ");
        for (int i = 0; i < NUM_SLIDERS; i++) {
            if (i > 0) detailText.append(", ");
            detailText.append(attackValues[i]);
        }
        
        // Position text above the attack frame
        int frameX = (screenWidth - FRAME_WIDTH) / 2;
        int frameY = screenHeight - FRAME_HEIGHT - 80;
        int textX = frameX;
        int textY = frameY - 30;
        
        context.drawText(MinecraftClient.getInstance().textRenderer, resultText, textX, textY, 0xFFFFFF, true);
        context.drawText(MinecraftClient.getInstance().textRenderer, detailText.toString(), textX, textY + 12, 0xFFFFFF, true);
    }
    
    private void renderSlashAnimation(DrawContext context, int screenWidth, int screenHeight) {
        if (currentSlashFrame < 0 || currentSlashFrame >= SLASH_FRAMES) return;
        
        // Get current slash texture
        Identifier currentSlashTexture = SLASH_TEXTURES.get(currentSlashFrame);
        
        // Render in center of screen - 2x smaller than original size
        int slashWidth = 100;  // Half the original size
        int slashHeight = 100; // Half the original size
        int slashX = (screenWidth - slashWidth) / 2;
        int slashY = (screenHeight - slashHeight) / 2;
        
        // Draw the current slash frame
        context.drawTexture(currentSlashTexture, slashX, slashY, 0, 0, slashWidth, slashHeight, slashWidth, slashHeight);
    }
}