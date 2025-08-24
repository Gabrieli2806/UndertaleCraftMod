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
 * Undertale attack overlay that renders on the HUD (similar to AnimationPlayer)
 */
public class UndertaleAttackOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("UndertaleAttackOverlay");
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
    
    // Frame dimensions (smaller size for better positioning)
    private static final int FRAME_WIDTH = 400;  // Smaller width
    private static final int FRAME_HEIGHT = 90;  // Smaller height
    
    // Slider dimensions (made thinner)
    private static final int SLIDER_WIDTH = 4;  // Thinner slider (was 14)
    private static final int SLIDER_HEIGHT = 120;
    
    // Slider movement state
    private float sliderPosition = 0.0f; // 0.0 = left, 1.0 = right
    private float sliderSpeed = 0.02f;   // Speed of movement
    private boolean sliderMoving = true; // true = still moving, false = stopped
    private boolean sliderFinished = false; // true = reached end or clicked
    private int attackValue = 0; // 0-100 value based on click position
    private boolean showResult = false; // show the result value
    
    // Slash animation state
    private boolean playingSlashAnimation = false;
    private int currentSlashFrame = 0;
    private long lastSlashFrameTime = 0;
    private static final int SLASH_FRAME_DELAY = 3; // ticks between slash frames (faster animation)
    
    public UndertaleAttackOverlay() {
        registerHudRender();
        registerEvents();
    }
    
    private void registerHudRender() {
        // Register HUD render callback (similar to AnimationPlayer)
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (isActive) {
                renderAttackOverlay(drawContext, tickDelta);
            }
        });
    }
    
    private void registerEvents() {
        // Register tick events for slider animation, slash animation, and input checking
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isActive) {
                if (sliderTextureLoaded && sliderMoving) {
                    updateSliderPosition();
                }
                if (playingSlashAnimation) {
                    updateSlashAnimation();
                }
                checkForClick(client);
            }
        });
    }
    
    private void checkForClick(MinecraftClient client) {
        // Check if left mouse button was clicked during slider movement
        if (sliderMoving && client.mouse.wasLeftButtonClicked()) {
            sliderMoving = false;
            sliderFinished = true;
            calculateAttackValue();
            LOGGER.info("Slider stopped by click at position {}, attack value: {}", sliderPosition, attackValue);
        }
    }
    
    private void updateSliderPosition() {
        // Move slider only left to right (once)
        if (sliderMoving) {
            sliderPosition += sliderSpeed;
            if (sliderPosition >= 1.0f) {
                sliderPosition = 1.0f;
                sliderMoving = false; // Stop moving
                sliderFinished = true; // Finished at end
                calculateAttackValue(); // Calculate value when it reaches the end
                LOGGER.info("Slider reached end automatically, attack value: {}", attackValue);
            }
        }
    }
    
    private void updateSlashAnimation() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        
        long currentTime = client.world.getTime();
        
        if (currentTime - lastSlashFrameTime >= SLASH_FRAME_DELAY) {
            currentSlashFrame++;
            lastSlashFrameTime = currentTime;
            
            // Stop animation after all frames
            if (currentSlashFrame >= SLASH_FRAMES) {
                playingSlashAnimation = false;
                currentSlashFrame = 0;
                LOGGER.info("Slash animation completed");
            }
        }
    }
    
    private void calculateAttackValue() {
        // Convert slider position (0.0-1.0) to attack value (0-100)
        // Position 50 (middle) = Score 100 (best), Position 0/100 (edges) = Score 0 (worst)
        
        // Convert slider position (0.0-1.0) to position value (0-100)
        float positionValue = sliderPosition * 100; // 0.0 -> 0, 0.5 -> 50, 1.0 -> 100
        
        // Calculate distance from perfect center (50)
        float distanceFrom50 = Math.abs(positionValue - 50.0f); // 0 at center, 50 at edges
        
        // Convert distance to score: 0 distance = 100 score, 50 distance = 0 score
        attackValue = Math.round(100 - (distanceFrom50 * 2)); // Linear scoring
        attackValue = Math.max(0, Math.min(100, attackValue)); // Clamp to 0-100
        
        // Start slash animation only if attack was successful (score > 0)
        if (attackValue > 0) {
            startSlashAnimation();
        }
        
        showResult = true;
        
        // Send attack value to server for scoreboard
        sendAttackValueToServer(attackValue);
        
        // Close overlay after appropriate delay
        new Thread(() -> {
            try {
                if (attackValue > 0) {
                    // Wait for slash animation to complete (5 frames * 3 ticks * 50ms per tick = ~0.75 seconds)
                    Thread.sleep(1500); // Extra buffer time
                } else {
                    // No animation, close faster for misses
                    Thread.sleep(1000);
                }
                stopAttack();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    private void startSlashAnimation() {
        if (!slashTexturesLoaded) return;
        
        playingSlashAnimation = true;
        currentSlashFrame = 0;
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != null) {
            lastSlashFrameTime = client.world.getTime();
        }
        
        LOGGER.info("Started slash animation");
    }
    
    private void sendAttackValueToServer(int attackValue) {
        try {
            // Create packet with attack value
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeInt(attackValue);
            
            // Send to server
            ClientPlayNetworking.send(UndertaleNetworking.ATTACK_VALUE_PACKET, buf);
            LOGGER.info("Sent attack value {} to server for scoreboard", attackValue);
            
        } catch (Exception e) {
            LOGGER.warn("Failed to send attack value to server", e);
        }
    }
    
    private void loadTextures() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getResourceManager() == null) {
            LOGGER.warn("ResourceManager not available for texture loading");
            return;
        }
        
        try {
            // Check if attack frame texture exists
            var frameResource = client.getResourceManager().getResource(ATTACK_FRAME_TEXTURE);
            texturesLoaded = frameResource.isPresent();
            
            // Check if slider texture exists
            var sliderResource = client.getResourceManager().getResource(ATTACK_SLIDER_TEXTURE);
            sliderTextureLoaded = sliderResource.isPresent();
            
            // Check slash animation textures
            slashTexturesLoaded = true;
            for (int i = 0; i < SLASH_FRAMES; i++) {
                var slashResource = client.getResourceManager().getResource(SLASH_TEXTURES.get(i));
                if (slashResource.isEmpty()) {
                    slashTexturesLoaded = false;
                    break;
                }
            }
            
            if (texturesLoaded) {
                LOGGER.info("Attack frame texture loaded for overlay: {}", ATTACK_FRAME_TEXTURE);
            } else {
                LOGGER.info("Attack frame texture NOT found for overlay: {}, using fallback", ATTACK_FRAME_TEXTURE);
            }
            
            if (sliderTextureLoaded) {
                LOGGER.info("Attack slider texture loaded for overlay: {}", ATTACK_SLIDER_TEXTURE);
            } else {
                LOGGER.info("Attack slider texture NOT found for overlay: {}, using fallback", ATTACK_SLIDER_TEXTURE);
            }
            
            if (slashTexturesLoaded) {
                LOGGER.info("All {} slash animation textures loaded successfully", SLASH_FRAMES);
            } else {
                LOGGER.info("Some slash animation textures missing, animation will be skipped");
            }
            
        } catch (Exception e) {
            LOGGER.warn("Failed to load attack textures for overlay", e);
            texturesLoaded = false;
            sliderTextureLoaded = false;
            slashTexturesLoaded = false;
        }
    }
    
    private void renderAttackOverlay(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        
        // Get screen dimensions
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        
        // Position closer to hotbar
        int frameX = (screenWidth - FRAME_WIDTH) / 2;
        int frameY = screenHeight - FRAME_HEIGHT - 30; // Only 30 pixels above bottom (closer to hotbar)
        
        if (texturesLoaded) {
            // Render attack frame texture
            context.drawTexture(ATTACK_FRAME_TEXTURE, frameX, frameY, 0, 0, FRAME_WIDTH, FRAME_HEIGHT, FRAME_WIDTH, FRAME_HEIGHT);
        } else {
            // Fallback: simple colored rectangle
            int borderColor = 0xFFFFFFFF; // White border
            int bgColor = 0x88000000;     // Semi-transparent black background
            
            context.fill(frameX - 2, frameY - 2, frameX + FRAME_WIDTH + 2, frameY + FRAME_HEIGHT + 2, borderColor);
            context.fill(frameX, frameY, frameX + FRAME_WIDTH, frameY + FRAME_HEIGHT, bgColor);
        }
        
        // Render sliding attack meter
        renderSlider(context, frameX, frameY, client);
        
        // Render slash animation in center of screen
        if (playingSlashAnimation && slashTexturesLoaded) {
            renderSlashAnimation(context, screenWidth, screenHeight);
        }
        
        // Show step text or result
        String displayText;
        int textColor;
        
        if (showResult) {
            // Show the attack result value (higher score is better now)
            if (attackValue >= 90) {
                displayText = "PERFECT! (" + attackValue + ")";
                textColor = 0xFF00FF00; // Green
            } else if (attackValue >= 70) {
                displayText = "Great! (" + attackValue + ")";
                textColor = 0xFF80FF80; // Light Green
            } else if (attackValue >= 50) {
                displayText = "Good! (" + attackValue + ")";
                textColor = 0xFFFFFF00; // Yellow
            } else if (attackValue >= 25) {
                displayText = "Okay (" + attackValue + ")";
                textColor = 0xFFFF8800; // Orange
            } else {
                displayText = "Miss... (" + attackValue + ")";
                textColor = 0xFFFF0000; // Red
            }
        } else {
            // No text when slider is moving or waiting
            displayText = null;
            textColor = 0xFFFFFFFF; // White (unused)
        }
        
        // Draw text centered above the frame (only if there's text to show)
        if (displayText != null) {
            int textWidth = client.textRenderer.getWidth(displayText);
            int textX = (screenWidth - textWidth) / 2;
            int textY = frameY - 30;
            context.drawText(client.textRenderer, displayText, textX, textY, textColor, true);
        }
        
        LOGGER.debug("Rendering attack overlay at position ({}, {})", frameX, frameY);
    }
    
    private void renderSlider(DrawContext context, int frameX, int frameY, MinecraftClient client) {
        // Calculate slider position - moves across the middle of the frame
        int sliderAreaWidth = FRAME_WIDTH - SLIDER_WIDTH; // Available area for slider movement
        int sliderX = frameX + (int)(sliderPosition * sliderAreaWidth);
        
        // Position slider in the middle (vertically) of the frame
        // Since slider height (120) is taller than frame height (90), center it vertically
        int sliderY = frameY + (FRAME_HEIGHT / 2) - (SLIDER_HEIGHT / 2);
        
        if (sliderTextureLoaded) {
            // Render slider texture
            context.drawTexture(ATTACK_SLIDER_TEXTURE, sliderX, sliderY, 0, 0, SLIDER_WIDTH, SLIDER_HEIGHT, SLIDER_WIDTH, SLIDER_HEIGHT);
        } else {
            // Fallback: red rectangle slider
            int sliderColor = 0xFFFF0000; // Red
            context.fill(sliderX, sliderY, sliderX + SLIDER_WIDTH, sliderY + SLIDER_HEIGHT, sliderColor);
        }
        
        LOGGER.debug("Rendering slider at position ({}, {}) with slider position {}", sliderX, sliderY, sliderPosition);
    }
    
    private void renderSlashAnimation(DrawContext context, int screenWidth, int screenHeight) {
        if (currentSlashFrame < 0 || currentSlashFrame >= SLASH_FRAMES) return;
        
        // Get current slash texture
        Identifier currentSlashTexture = SLASH_TEXTURES.get(currentSlashFrame);
        
        // Render in center of screen - 2x smaller than original size
        int slashWidth = 100;  // Half the original size (200 -> 100)
        int slashHeight = 100; // Half the original size (200 -> 100)
        int slashX = (screenWidth - slashWidth) / 2;
        int slashY = (screenHeight - slashHeight) / 2;
        
        // Draw the current slash frame
        context.drawTexture(currentSlashTexture, slashX, slashY, 0, 0, slashWidth, slashHeight, slashWidth, slashHeight);
        
        LOGGER.debug("Rendering slash animation frame {} at center ({}, {})", currentSlashFrame + 1, slashX, slashY);
    }
    
    /**
     * Start showing the attack overlay
     */
    public void startAttack() {
        if (!texturesLoaded || !sliderTextureLoaded) {
            loadTextures();
        }
        
        // Reset all states
        sliderPosition = 0.0f;
        sliderMoving = true;
        sliderFinished = false;
        attackValue = 0;
        showResult = false;
        
        // Reset slash animation state
        playingSlashAnimation = false;
        currentSlashFrame = 0;
        lastSlashFrameTime = 0;
        
        isActive = true;
        LOGGER.info("Attack overlay started - slider moves left to right, click to stop and trigger slash animation!");
    }
    
    /**
     * Stop showing the attack overlay
     */
    public void stopAttack() {
        isActive = false;
        LOGGER.info("Attack overlay stopped");
    }
    
    /**
     * Check if overlay is currently active
     */
    public boolean isActive() {
        return isActive;
    }
    
    /**
     * Toggle the overlay on/off
     */
    public void toggle() {
        if (isActive) {
            stopAttack();
        } else {
            startAttack();
        }
    }
}