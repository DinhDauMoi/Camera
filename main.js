// Vintage Camera JS Logic

// State management
let stream = null;
let currentZoom = 1.0;
let activeFilter = 'normal';
let isSimulatorMode = false;
let gallery = [];
let currentGalleryIndex = 0;
let currentFacingMode = 'environment';


// Filter styling mappings (CSS filter values for canvas and video)
const filterCSS = {
  'normal': 'none',
  'retro-chrome': 'saturate(1.7) contrast(1.15) brightness(0.95)',
  'lomo-gold': 'sepia(0.25) saturate(1.4) contrast(1.1) brightness(0.95) hue-rotate(-5deg)',
  'noir-grain': 'grayscale(1) contrast(1.35) brightness(0.9)',
  'vhs-glitch': 'contrast(1.1) saturate(1.3) hue-rotate(15deg) sepia(0.1)',
  'sunset': 'sepia(0.2) saturate(1.4) hue-rotate(-15deg) contrast(1.15) brightness(0.9)'
};

const filterDisplayNames = {
  'normal': 'Gốc',
  'retro-chrome': 'Retro Chrome',
  'lomo-gold': 'Lomo Gold',
  'noir-grain': 'Noir Grain',
  'vhs-glitch': 'VHS Glitch',
  'sunset': 'Sunset Glow'
};

// DOM Elements
const video = document.getElementById('camera-stream');
const placeholder = document.getElementById('viewfinder-placeholder');
const permissionNotice = document.getElementById('permission-notice');
const btnAllowCamera = document.getElementById('btn-allow-camera');
const btnSkipCamera = document.getElementById('btn-skip-camera');
const btnShutter = document.getElementById('btn-shutter');
const btnGallery = document.getElementById('btn-gallery');
const btnFilter = document.getElementById('btn-filter');
const flashOverlay = document.getElementById('flash-overlay');
const zoomBtns = document.querySelectorAll('.zoom-btn');
const statusTime = document.getElementById('status-time');
const viewfinderDate = document.getElementById('viewfinder-date');

// Drawers
const filterDrawer = document.getElementById('filter-drawer');
const galleryDrawer = document.getElementById('gallery-drawer');
const closeFilters = document.getElementById('close-filters');
const closeGallery = document.getElementById('close-gallery');
const filterOptions = document.querySelectorAll('.filter-option');

// Gallery components
const galleryEmpty = document.getElementById('gallery-empty');
const slideViewer = document.getElementById('retro-slide-viewer');
const currentSlideImg = document.getElementById('current-slide-img');
const slideDateStamp = document.getElementById('slide-date-stamp');
const slideFilterLabel = document.getElementById('slide-filter-label');
const slideIndex = document.getElementById('slide-index');
const btnPrev = document.getElementById('nav-prev');
const btnNext = document.getElementById('nav-next');
const btnDownload = document.getElementById('btn-download');
const btnDelete = document.getElementById('btn-delete');
const galleryBadge = document.getElementById('gallery-badge');

// Initialization
document.addEventListener('DOMContentLoaded', () => {
  updateStatusTime();
  setInterval(updateStatusTime, 60000);
  
  // Set current date on viewfinder
  const today = getFormattedDate();
  viewfinderDate.textContent = today;

  // Load photos from local storage
  loadGallery();

  // Bind Event Listeners
  btnAllowCamera.addEventListener('click', requestCameraPermission);
  btnSkipCamera.addEventListener('click', enableSimulatorMode);
  
  const btnFlipCamera = document.getElementById('btn-flip-camera');
  if (btnFlipCamera) {
    btnFlipCamera.addEventListener('click', toggleCameraFacingMode);
  }
  
  // Zoom Controls
  zoomBtns.forEach(btn => {
    btn.addEventListener('click', (e) => {
      const zoomVal = parseFloat(e.target.dataset.zoom);
      setZoom(zoomVal);
      // Toggle active classes
      zoomBtns.forEach(b => b.classList.remove('active'));
      e.target.classList.add('active');
    });
  });

  // Drawer Toggles
  btnFilter.addEventListener('click', () => {
    filterDrawer.classList.toggle('open');
    galleryDrawer.classList.remove('open');
  });

  btnGallery.addEventListener('click', () => {
    galleryDrawer.classList.toggle('open');
    filterDrawer.classList.remove('open');
    if (galleryDrawer.classList.contains('open')) {
      playWindingSound();
    }
  });

  closeFilters.addEventListener('click', () => filterDrawer.classList.remove('open'));
  closeGallery.addEventListener('click', () => galleryDrawer.classList.remove('open'));

  // Filter Selection
  filterOptions.forEach(opt => {
    opt.addEventListener('click', (e) => {
      const optElement = e.currentTarget;
      const filterName = optElement.dataset.filter;
      
      filterOptions.forEach(o => o.classList.remove('active'));
      optElement.classList.add('active');
      
      applyViewfinderFilter(filterName);
    });
  });

  // Shutter action
  btnShutter.addEventListener('click', capturePhoto);

  // Gallery Navigation & Actions
  btnPrev.addEventListener('click', showPreviousSlide);
  btnNext.addEventListener('click', showNextSlide);
  btnDownload.addEventListener('click', downloadCurrentPhoto);
  btnDelete.addEventListener('click', deleteCurrentPhoto);

  // Attempt auto-start camera
  requestCameraPermission();
});

// Update Simulated status time
function updateStatusTime() {
  const now = new Date();
  let hours = now.getHours().toString().padStart(2, '0');
  let minutes = now.getMinutes().toString().padStart(2, '0');
  statusTime.textContent = `${hours}:${minutes}`;
}

// Format date for camera imprint: DD/MM/YYYY
function getFormattedDate() {
  const now = new Date();
  const d = now.getDate().toString().padStart(2, '0');
  const m = (now.getMonth() + 1).toString().padStart(2, '0');
  const y = now.getFullYear();
  return `${d}/${m}/${y}`;
}

// Camera Access
async function requestCameraPermission() {
  try {
    if (stream) {
      stream.getTracks().forEach(track => track.stop());
    }

    const constraints = {
      video: {
        facingMode: currentFacingMode,
        width: { ideal: 1080 },
        height: { ideal: 1350 }
      },
      audio: false
    };

    try {
      stream = await navigator.mediaDevices.getUserMedia(constraints);
    } catch (err) {
      console.warn("Preferred facingMode failed, trying generic video constraints:", err);
      // Fallback to generic camera
      stream = await navigator.mediaDevices.getUserMedia({
        video: true,
        audio: false
      });
    }

    video.srcObject = stream;
    video.style.display = 'block';
    placeholder.style.display = 'none';
    isSimulatorMode = false;
    permissionNotice.classList.add('hidden');
    
    // Show flip camera button when actual camera is working
    const btnFlipCamera = document.getElementById('btn-flip-camera');
    if (btnFlipCamera) {
      btnFlipCamera.style.display = 'flex';
    }
    
    // Focus effect
    triggerFocusIndicator();
  } catch (error) {
    console.warn("Camera access denied or failed, launching Simulator Mode.", error);
    enableSimulatorMode();
  }
}

// Toggle Camera Front/Back
function toggleCameraFacingMode() {
  if (isSimulatorMode) return;
  currentFacingMode = currentFacingMode === 'environment' ? 'user' : 'environment';
  
  // Rotate the flip button animation for haptic feel
  const btnFlipCamera = document.getElementById('btn-flip-camera');
  if (btnFlipCamera) {
    btnFlipCamera.style.transform = 'scale(0.9) rotate(180deg)';
    setTimeout(() => {
      btnFlipCamera.style.transform = '';
    }, 300);
  }
  
  // Re-request camera stream with new facing mode
  requestCameraPermission();
}

// Fallback simulator mode
function enableSimulatorMode() {
  isSimulatorMode = true;
  video.style.display = 'none';
  placeholder.style.display = 'flex';
  permissionNotice.classList.add('hidden');
  
  // Hide flip camera button in Simulator Mode
  const btnFlipCamera = document.getElementById('btn-flip-camera');
  if (btnFlipCamera) {
    btnFlipCamera.style.display = 'none';
  }
  
  // Apply visual effect to mockup scenery
  applyViewfinderFilter(activeFilter);
}

// Digital Zoom logic
function setZoom(factor) {
  currentZoom = factor;
  // Apply scaling transform on the target viewfinder element
  const target = isSimulatorMode ? placeholder : video;
  target.style.transform = `scale(${factor})`;
  
  // Trigger short focusing bracket animation
  triggerFocusIndicator();
}

// Trigger Focus Corner Box Animation
function triggerFocusIndicator() {
  const brackets = document.querySelector('.viewfinder-focus-brackets');
  brackets.classList.remove('focusing');
  void brackets.offsetWidth; // Reflow trigger
  brackets.classList.add('focusing');
  
  // Play subtle sound or stop focus indicator after 1s
  setTimeout(() => {
    brackets.classList.remove('focusing');
  }, 1000);
}

// Filter Viewport Effect
function applyViewfinderFilter(filterName) {
  activeFilter = filterName;
  const filterStyle = filterCSS[filterName] || 'none';
  
  // Apply style to either active video or mock scenery
  video.style.filter = filterStyle;
  placeholder.style.filter = filterStyle;
}

// Play synthesized camera sound (Web Audio API)
function playShutterSound() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    
    // Click sound (White Noise with fast decay)
    const bufferSize = ctx.sampleRate * 0.08;
    const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < bufferSize; i++) {
      data[i] = Math.random() * 2 - 1;
    }
    
    const noise = ctx.createBufferSource();
    noise.buffer = buffer;
    
    const filter = ctx.createBiquadFilter();
    filter.type = 'highpass';
    filter.frequency.setValueAtTime(1200, ctx.currentTime);
    
    const gainNode = ctx.createGain();
    gainNode.gain.setValueAtTime(0.8, ctx.currentTime);
    gainNode.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.07);
    
    noise.connect(filter);
    filter.connect(gainNode);
    gainNode.connect(ctx.destination);
    noise.start();
    
    // Mirror/Shutter Slap (low frequency transient)
    const osc = ctx.createOscillator();
    const oscGain = ctx.createGain();
    osc.type = 'triangle';
    osc.frequency.setValueAtTime(180, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(30, ctx.currentTime + 0.15);
    
    oscGain.gain.setValueAtTime(0.6, ctx.currentTime);
    oscGain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.14);
    
    osc.connect(oscGain);
    oscGain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.15);
    
    // Winding motor sound (simulated film feed right after photo)
    setTimeout(() => {
      playWindingSound();
    }, 250);
  } catch (err) {
    console.error("Audio Context click failed", err);
  }
}

// Film wind-up sound
function playWindingSound() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const duration = 0.45;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    
    osc.type = 'sawtooth';
    // Frequency hum (pulsating like gears rotating)
    osc.frequency.setValueAtTime(90, ctx.currentTime);
    osc.frequency.linearRampToValueAtTime(160, ctx.currentTime + duration * 0.4);
    osc.frequency.linearRampToValueAtTime(110, ctx.currentTime + duration);
    
    // Volume envelope
    gain.gain.setValueAtTime(0, ctx.currentTime);
    gain.gain.linearRampToValueAtTime(0.12, ctx.currentTime + 0.05);
    // Vibrato/Gear tick simulation using simple low frequency modulation
    const mod = ctx.createOscillator();
    const modGain = ctx.createGain();
    mod.frequency.value = 16; // Ticks per second
    modGain.gain.value = 0.05;
    
    mod.connect(modGain);
    modGain.connect(gain.gain);
    
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration);
    
    osc.connect(gain);
    gain.connect(ctx.destination);
    
    mod.start();
    osc.start();
    
    mod.stop(ctx.currentTime + duration);
    osc.stop(ctx.currentTime + duration);
  } catch (e) {
    console.warn("Winding sound failed", e);
  }
}

// Capture Photo
function capturePhoto() {
  // Shutter press animation
  btnShutter.classList.add('pressed');
  setTimeout(() => btnShutter.classList.remove('pressed'), 150);
  
  // Flash effect
  flashOverlay.classList.remove('flash-active');
  void flashOverlay.offsetWidth;
  flashOverlay.classList.add('flash-active');
  
  // Play sounds
  playShutterSound();
  
  const canvas = document.getElementById('capture-canvas');
  const ctx = canvas.getContext('2d');
  
  // Output resolutions: typical high-quality retro crop 1080 x 1080 (Square slide mount format)
  const size = 1080;
  canvas.width = size;
  canvas.height = size;
  
  // Function to apply filters programmatically to canvas context
  ctx.filter = filterCSS[activeFilter] || 'none';
  
  if (isSimulatorMode) {
    // Generative custom scene or render public/retro-scenery.png
    const img = new Image();
    img.src = '/retro-scenery.png';
    img.onload = () => {
      // Draw background
      ctx.drawImage(img, 0, 0, size, size);
      
      // Draw dynamic elements on simulator capture to make each photo unique!
      drawGenerativeElements(ctx, size);
      
      // Apply filters and overlays
      finalizeCapture(canvas, ctx);
    };
  } else {
    // Draw live webcam feed frame
    // Calculate source cropping to maintain a perfect square frame
    const vWidth = video.videoWidth;
    const vHeight = video.videoHeight;
    const minSize = Math.min(vWidth, vHeight);
    const sx = (vWidth - minSize) / 2;
    const sy = (vHeight - minSize) / 2;
    
    // Draw cropped center of video
    // Adjust for current digital zoom factor
    const zoomOffset = (minSize * (1 - 1/currentZoom)) / 2;
    const sZoomSize = minSize / currentZoom;
    
    ctx.drawImage(
      video, 
      sx + zoomOffset, 
      sy + zoomOffset, 
      sZoomSize, 
      sZoomSize, 
      0, 
      0, 
      size, 
      size
    );
    
    finalizeCapture(canvas, ctx);
  }
}

// Draw dynamic vector features on simulator mode so every picture is unique
function drawGenerativeElements(ctx, size) {
  // Clear filter settings temporary to draw solid colorful items
  const tempFilter = ctx.filter;
  ctx.filter = 'none';

  // Draw some birds floating in the sky
  ctx.strokeStyle = '#1E293B';
  ctx.lineWidth = 4;
  ctx.lineCap = 'round';
  
  const numBirds = 2 + Math.floor(Math.random() * 4);
  for (let i = 0; i < numBirds; i++) {
    const bx = 200 + Math.random() * 600;
    const by = 80 + Math.random() * 150;
    const bSize = 15 + Math.random() * 20;
    
    ctx.beginPath();
    ctx.arc(bx - bSize/2, by, bSize/2, Math.PI, 0, false);
    ctx.arc(bx + bSize/2, by, bSize/2, Math.PI, 0, false);
    ctx.stroke();
  }

  // Draw a cute hot air balloon or sun rays
  if (Math.random() > 0.4) {
    const bx = 750 + Math.random() * 100;
    const by = 150 + Math.random() * 100;
    
    // Draw balloon envelope (red and white stripes)
    ctx.fillStyle = '#EF4444';
    ctx.beginPath();
    ctx.arc(bx, by, 30, 0, Math.PI * 2);
    ctx.fill();
    
    ctx.fillStyle = '#FFFFFF';
    ctx.beginPath();
    ctx.ellipse(bx, by, 10, 30, 0, 0, Math.PI * 2);
    ctx.fill();
    
    // basket
    ctx.fillStyle = '#78350F';
    ctx.fillRect(bx - 6, by + 40, 12, 10);
    
    // ropes
    ctx.strokeStyle = '#4B5563';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(bx - 5, by + 30);
    ctx.lineTo(bx - 5, by + 40);
    ctx.moveTo(bx + 5, by + 30);
    ctx.lineTo(bx + 5, by + 40);
    ctx.stroke();
  }

  // Reapply retro filters
  ctx.filter = tempFilter;
}

// Bakes the film texture, date stamps, and pushes into gallery array
function finalizeCapture(canvas, ctx) {
  // Draw Film Grain and dust noise
  ctx.fillStyle = 'rgba(255, 255, 255, 0.04)';
  for (let i = 0; i < 8000; i++) {
    const rx = Math.random() * canvas.width;
    const ry = Math.random() * canvas.height;
    ctx.fillRect(rx, ry, 1.5, 1.5);
  }
  
  // Draw subtle retro vignettes (darkened circular border)
  const gradient = ctx.createRadialGradient(
    canvas.width/2, canvas.height/2, canvas.width * 0.4,
    canvas.width/2, canvas.height/2, canvas.width * 0.72
  );
  gradient.addColorStop(0, 'rgba(0,0,0,0)');
  gradient.addColorStop(1, 'rgba(0,0,0,0.32)');
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  
  // Bake retro date stamp (bright orange Courier)
  ctx.filter = 'none'; // text shouldn't be blurry
  ctx.fillStyle = '#EF4444';
  ctx.font = 'bold 36px "Space Mono", "Courier New", monospace';
  ctx.shadowColor = 'rgba(0, 0, 0, 0.6)';
  ctx.shadowBlur = 4;
  ctx.shadowOffsetX = 2;
  ctx.shadowOffsetY = 2;
  
  const textDate = getFormattedDate();
  ctx.fillText(textDate, canvas.width - 290, canvas.height - 45);
  
  // Reset shadow effects
  ctx.shadowColor = 'transparent';
  ctx.shadowBlur = 0;
  ctx.shadowOffsetX = 0;
  ctx.shadowOffsetY = 0;

  // Convert to image source
  const dataURL = canvas.toDataURL('image/jpeg', 0.9);
  
  // Create photo data record
  const photo = {
    id: Date.now(),
    src: dataURL,
    date: textDate,
    filter: filterDisplayNames[activeFilter] || 'Gốc'
  };
  
  // Prepend to gallery
  gallery.unshift(photo);
  currentGalleryIndex = 0;
  
  // Save & update display
  saveGallery();
  updateGalleryUI();
  
  // Open the gallery drawer immediately to show the shot (UX highlight!)
  setTimeout(() => {
    galleryDrawer.classList.add('open');
    playWindingSound();
  }, 400);
}

// Local Storage actions
function saveGallery() {
  localStorage.setItem('retro_cam_gallery', JSON.stringify(gallery));
}

function loadGallery() {
  const saved = localStorage.getItem('retro_cam_gallery');
  if (saved) {
    gallery = JSON.parse(saved);
  } else {
    // Populate with 1 demo photo (using our beautiful public/retro-scenery.png)
    gallery = [{
      id: 1,
      src: '/retro-scenery.png',
      date: getFormattedDate(),
      filter: 'Retro Chrome'
    }];
    saveGallery();
  }
  
  updateGalleryUI();
}

// Sync UI Elements to Gallery Array
function updateGalleryUI() {
  // Update badge count
  if (gallery.length > 0) {
    galleryBadge.textContent = gallery.length;
    galleryBadge.style.transform = 'scale(1.2)';
    setTimeout(() => galleryBadge.style.transform = 'scale(1)', 200);
    galleryBadge.style.display = 'flex';
    
    // show slide frame elements
    slideViewer.style.display = 'flex';
    galleryEmpty.style.display = 'none';
    
    // load current slide contents
    const curPhoto = gallery[currentGalleryIndex];
    currentSlideImg.src = curPhoto.src;
    slideDateStamp.textContent = curPhoto.date;
    slideFilterLabel.textContent = curPhoto.filter;
    slideIndex.textContent = `${(currentGalleryIndex + 1).toString().padStart(2, '0')} / ${gallery.length.toString().padStart(2, '0')}`;
  } else {
    galleryBadge.style.display = 'none';
    slideViewer.style.display = 'none';
    galleryEmpty.style.display = 'block';
  }
}

// Slide Carousel Navigate
function showPreviousSlide() {
  if (gallery.length <= 1) return;
  
  // Simple slide mount animation
  const mount = document.querySelector('.slide-mount');
  mount.style.transform = 'translateX(20px) rotate(-1deg)';
  mount.style.opacity = '0.7';
  
  setTimeout(() => {
    currentGalleryIndex = (currentGalleryIndex - 1 + gallery.length) % gallery.length;
    updateGalleryUI();
    playWindingSound();
    
    mount.style.transform = 'translateX(-20px) rotate(1deg)';
    void mount.offsetWidth; // force redraw
    mount.style.transform = 'translateX(0) rotate(0)';
    mount.style.opacity = '1';
  }, 150);
}

function showNextSlide() {
  if (gallery.length <= 1) return;
  
  const mount = document.querySelector('.slide-mount');
  mount.style.transform = 'translateX(-20px) rotate(1deg)';
  mount.style.opacity = '0.7';
  
  setTimeout(() => {
    currentGalleryIndex = (currentGalleryIndex + 1) % gallery.length;
    updateGalleryUI();
    playWindingSound();
    
    mount.style.transform = 'translateX(20px) rotate(-1deg)';
    void mount.offsetWidth;
    mount.style.transform = 'translateX(0) rotate(0)';
    mount.style.opacity = '1';
  }, 150);
}

// Download Active Photo
function downloadCurrentPhoto() {
  if (gallery.length === 0) return;
  
  const curPhoto = gallery[currentGalleryIndex];
  const downloadLink = document.createElement('a');
  downloadLink.href = curPhoto.src;
  downloadLink.download = `retro_cam_pic_${curPhoto.id}.jpg`;
  document.body.appendChild(downloadLink);
  downloadLink.click();
  document.body.removeChild(downloadLink);
}

// Delete Active Photo
function deleteCurrentPhoto() {
  if (gallery.length === 0) return;
  
  if (confirm("Bạn có chắc chắn muốn xóa bức ảnh này khỏi cuộn phim?")) {
    gallery.splice(currentGalleryIndex, 1);
    
    // adjust index
    if (currentGalleryIndex >= gallery.length && gallery.length > 0) {
      currentGalleryIndex = gallery.length - 1;
    }
    
    saveGallery();
    updateGalleryUI();
  }
}
