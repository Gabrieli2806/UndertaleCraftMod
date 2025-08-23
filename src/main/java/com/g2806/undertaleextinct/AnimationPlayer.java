package com.g2806.undertaleextinct;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles fullscreen animation playback using image sequences
 */
public class AnimationPlayer {
    private static final Logger LOGGER = LoggerFactory.getLogger("AnimationPlayer");
    private static final String MOD_ID = "undertaleextinct";
    
    // Animation state
    private boolean isPlaying = false;
    private int currentFrame = 0;
    private int totalFrames = 94; // Based on the frames in resources
    private long lastFrameTime = 0;
    private int frameDelay = 3; // Ticks between frames (20 ticks = 1 second)
    
    // Texture management
    private List<Identifier> frameTextures = new ArrayList<>();
    private boolean texturesLoaded = false;
    
    // UI state management
    private Screen originalScreen = null;
    private AnimationScreen animationScreen = null;
    
    // Custom screen for fullscreen animation rendering
    private class AnimationScreen extends Screen {
        protected AnimationScreen() {
            super(Text.literal("Animation"));
        }
        
        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            if (isPlaying && texturesLoaded && frameTextures.size() > currentFrame) {
                // Get screen dimensions
                int screenWidth = this.width;
                int screenHeight = this.height;
                
                // Clear the entire screen with black background
                context.fill(0, 0, screenWidth, screenHeight, 0xFF000000);
                
                // Render the current frame fullscreen
                Identifier currentTexture = frameTextures.get(currentFrame);
                context.drawTexture(currentTexture, 0, 0, 0, 0, screenWidth, screenHeight, screenWidth, screenHeight);
            }
        }
        
        @Override
        public boolean shouldPause() {
            return false; // Don't pause the game
        }
        
        @Override
        public boolean shouldCloseOnEsc() {
            return false; // Don't close on ESC to prevent interruption
        }
    }
    
    public AnimationPlayer() {
        registerEvents();
    }
    
    private void loadFrameTextures() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getResourceManager() == null) {
            LOGGER.warn("ResourceManager not available yet, deferring texture loading");
            return;
        }
        
        if (texturesLoaded) return; // Already loaded
        
        try {
            // Load all animation frames
            for (int i = 1; i <= totalFrames; i++) {
                String frameNumber = String.format("%03d", i);
                String texturePath = "textures/animation/ezgif-frame-" + frameNumber + ".jpg";
                Identifier frameId = new Identifier(MOD_ID, "animation_frame_" + frameNumber);
                
                // Load the texture from resources
                var resourceOpt = client.getResourceManager().getResource(new Identifier(MOD_ID, texturePath));
                if (resourceOpt.isEmpty()) {
                    LOGGER.warn("Could not find texture: {}", texturePath);
                    continue;
                }
                
                InputStream inputStream = resourceOpt.get().getInputStream();
                NativeImage image = NativeImage.read(inputStream);
                NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
                
                // Register the texture
                client.getTextureManager().registerTexture(frameId, texture);
                frameTextures.add(frameId);
                
                inputStream.close();
            }
            
            texturesLoaded = true;
            LOGGER.info("Loaded {} animation frames", frameTextures.size());
            
        } catch (IOException e) {
            LOGGER.error("Failed to load animation textures", e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error loading textures", e);
        }
    }
    
    private void registerEvents() {
        // Register tick event for animation updates
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isPlaying && texturesLoaded) {
                updateAnimation();
            }
        });
        
        // Animation is now handled by the custom AnimationScreen
        // No need for HUD callbacks since the screen handles all rendering
    }
    
    private void updateAnimation() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        
        long currentTime = client.world.getTime();
        
        if (currentTime - lastFrameTime >= frameDelay) {
            currentFrame++;
            lastFrameTime = currentTime;
            
            // Stop at the end (no looping)
            if (currentFrame >= totalFrames) {
                stopAnimation();
                return;
            }
        }
    }
    
    
    private void hideGameUI() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        
        // Store current screen
        originalScreen = client.currentScreen;
        
        // Create and show animation screen on the main thread
        client.execute(() -> {
            animationScreen = new AnimationScreen();
            client.setScreen(animationScreen);
        });
        
        LOGGER.info("Animation screen activated for fullscreen playback");
    }
    
    private void restoreGameUI() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        
        // Close animation screen and restore original screen on the main thread
        client.execute(() -> {
            if (client.currentScreen == animationScreen) {
                client.setScreen(originalScreen);
            }
            animationScreen = null;
            originalScreen = null;
        });
        
        LOGGER.info("Animation completed, original screen restored");
    }
    
    /**
     * Start playing the animation
     */
    public void startAnimation() {
        // Try to load textures if not loaded yet
        if (!texturesLoaded) {
            loadFrameTextures();
        }
        
        if (!texturesLoaded) {
            LOGGER.warn("Cannot start animation - textures not loaded");
            return;
        }
        
        // Hide all UI elements
        hideGameUI();
        
        isPlaying = true;
        currentFrame = 0;
        lastFrameTime = 0;
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != null) {
            lastFrameTime = client.world.getTime();
        }
        
        LOGGER.info("Animation started - playing {} frames with fullscreen overlay", totalFrames);
    }
    
    /**
     * Stop playing the animation
     */
    public void stopAnimation() {
        isPlaying = false;
        currentFrame = 0;
        
        // Restore UI elements
        restoreGameUI();
        
        LOGGER.info("Animation stopped - overlay removed");
    }
    
    /**
     * Check if animation is currently playing
     */
    public boolean isPlaying() {
        return isPlaying;
    }
    
    /**
     * Set the frame delay (ticks between frames)
     */
    public void setFrameDelay(int ticks) {
        this.frameDelay = Math.max(1, ticks);
    }
    
    /**
     * Get current frame number
     */
    public int getCurrentFrame() {
        return currentFrame;
    }
    
    /**
     * Get total number of frames
     */
    public int getTotalFrames() {
        return totalFrames;
    }
    
    /**
     * Set whether animation should loop
     */
    public void setLooping(boolean loop) {
        // This could be expanded to control looping behavior
        // For now, animation loops by default
    }
}