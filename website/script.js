const dialog = document.querySelector(".image-dialog");
const dialogImage = dialog?.querySelector("img");
const closeButton = dialog?.querySelector(".image-dialog-close");
const changelogTrigger = document.querySelector(".changelog-link");
const changelogDialog = document.querySelector(".changelog-dialog");
const changelogCloseButton = document.querySelector(".changelog-dialog-close");
const fullChangelogContent = document.getElementById("full-changelog-content");
const zoomState = {
  scale: 1,
  x: 0,
  y: 0,
  startScale: 1,
  startX: 0,
  startY: 0,
  startDistance: 0,
  startMidX: 0,
  startMidY: 0,
  pointers: new Map(),
};
const pageScrollLock = {
  isLocked: false,
  scrollY: 0,
};

const clamp = (value, min, max) => Math.min(Math.max(value, min), max);

const applyTransform = () => {
  if (!dialogImage) return;
  dialogImage.style.transform = `translate3d(${zoomState.x}px, ${zoomState.y}px, 0) scale(${zoomState.scale})`;
};

const resetZoom = () => {
  zoomState.scale = 1;
  zoomState.x = 0;
  zoomState.y = 0;
  zoomState.startScale = 1;
  zoomState.startX = 0;
  zoomState.startY = 0;
  zoomState.startDistance = 0;
  zoomState.startMidX = 0;
  zoomState.startMidY = 0;
  zoomState.pointers.clear();
  applyTransform();
};

const getDistance = (a, b) =>
  Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);

const getMidpoint = (a, b) => ({
  x: (a.clientX + b.clientX) / 2,
  y: (a.clientY + b.clientY) / 2,
});

const getPointerPair = () => [...zoomState.pointers.values()].slice(0, 2);

const lockPageScroll = () => {
  if (pageScrollLock.isLocked) return;

  pageScrollLock.scrollY = window.scrollY;
  document.body.style.position = "fixed";
  document.body.style.top = `-${pageScrollLock.scrollY}px`;
  document.body.style.left = "0";
  document.body.style.right = "0";
  document.body.style.width = "100%";
  pageScrollLock.isLocked = true;
};

const unlockPageScroll = () => {
  if (!pageScrollLock.isLocked) return;

  const previousScrollBehavior = document.documentElement.style.scrollBehavior;
  document.documentElement.style.scrollBehavior = "auto";
  document.body.style.position = "";
  document.body.style.top = "";
  document.body.style.left = "";
  document.body.style.right = "";
  document.body.style.width = "";
  window.scrollTo(0, pageScrollLock.scrollY);
  document.documentElement.style.scrollBehavior = previousScrollBehavior;
  pageScrollLock.isLocked = false;
};

const compareVersionsDesc = (a, b) => {
  const aParts = a.version.split(".").map((part) => Number.parseInt(part, 10));
  const bParts = b.version.split(".").map((part) => Number.parseInt(part, 10));
  const length = Math.max(aParts.length, bParts.length);

  for (let index = 0; index < length; index += 1) {
    const difference = (bParts[index] || 0) - (aParts[index] || 0);
    if (difference !== 0) return difference;
  }

  return b.version.localeCompare(a.version);
};

const parseChangelogMarkdown = (filename, markdown) => {
  const version = filename.replace(/\.md$/i, "");
  const metadataMatch = markdown.match(/^---\s*\n([\s\S]*?)\n---\s*/);
  const metadata = {};
  let body = markdown;

  if (metadataMatch) {
    body = markdown.slice(metadataMatch[0].length);
    metadataMatch[1].split(/\r?\n/).forEach((line) => {
      const match = line.match(/^([^:]+):\s*(.*)$/);
      if (!match) return;
      metadata[match[1].trim().toLowerCase()] = match[2].trim();
    });
  }

  const items = body
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => /^([-*+]|\d+\.)\s+/.test(line))
    .map((line) => line.replace(/^([-*+]|\d+\.)\s+/, "").trim())
    .filter(Boolean);

  return {
    version,
    date: metadata.data || metadata.date || "",
    items,
  };
};

const renderFullChangelog = (entries) => {
  if (!fullChangelogContent) return;

  fullChangelogContent.innerHTML = "";

  if (entries.length === 0) {
    const empty = document.createElement("p");
    empty.className = "changelog-empty";
    empty.textContent = "暂无更新日志";
    fullChangelogContent.appendChild(empty);
    return;
  }

  entries.forEach((entry) => {
    const section = document.createElement("section");
    section.className = "changelog-version";

    const heading = document.createElement("div");
    heading.className = "changelog-version-heading";

    const version = document.createElement("h3");
    version.textContent = `v${entry.version}`;

    const date = document.createElement("time");
    date.textContent = entry.date || "日期未填写";

    heading.append(version, date);
    section.appendChild(heading);

    const list = document.createElement("ul");
    if (entry.items.length > 0) {
      entry.items.forEach((item) => {
        const li = document.createElement("li");
        li.textContent = item;
        list.appendChild(li);
      });
    } else {
      const li = document.createElement("li");
      li.textContent = "暂无更新内容";
      list.appendChild(li);
    }

    section.appendChild(list);
    fullChangelogContent.appendChild(section);
  });
};

const loadUpdateNotes = async (data) => {
  const changelogUrl =
    data.notesUrl || (data.versionName ? `changelog/${data.versionName}.md` : "");
  if (changelogUrl) {
    try {
      const response = await fetch(changelogUrl);
      if (!response.ok) throw new Error("最新更新日志加载失败");

      const markdown = await response.text();
      const entry = parseChangelogMarkdown(`${data.versionName || ""}.md`, markdown);
      if (entry.items.length > 0) return entry.items;
    } catch (error) {
      console.warn("无法加载当前版本 changelog，回退到 update.json notes:", error);
    }
  }

  if (data.notes) {
    return data.notes
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => line.length > 0)
      .map((line) => line.replace(/^[-*•\d+\.\s]+/, ""))
      .filter(Boolean);
  }

  return [];
};

const loadFullChangelog = async () => {
  if (!fullChangelogContent) return;

  try {
    const listResponse = await fetch("changelog/index.json");
    if (!listResponse.ok) throw new Error("更新日志清单加载失败");

    const filenames = await listResponse.json();
    const entries = await Promise.all(
      filenames.map(async (filename) => {
        const response = await fetch(`changelog/${filename}`);
        if (!response.ok) throw new Error(`${filename} 加载失败`);
        const markdown = await response.text();
        return parseChangelogMarkdown(filename, markdown);
      }),
    );

    renderFullChangelog(entries.sort(compareVersionsDesc));
  } catch (error) {
    fullChangelogContent.innerHTML = "";
    const failed = document.createElement("p");
    failed.className = "changelog-empty";
    failed.textContent = "更新日志暂时无法加载";
    fullChangelogContent.appendChild(failed);
    console.warn("无法加载更新日志:", error);
  }
};

const initGsapAnimations = () => {
  if (!window.gsap) return;

  const { gsap } = window;
  const reduceMotion = window.matchMedia(
    "(prefers-reduced-motion: reduce)",
  ).matches;

  if (reduceMotion) {
    gsap.set(
      [
        ".github-link",
        ".logo-mark",
        "h1",
        ".tag-list span",
        ".tagline",
        ".download-button",
        ".hero-media",
        ".wave",
        ".feature-row",
        ".info-card",
        ".site-footer",
      ],
      { clearProps: "all" },
    );
    return;
  }

  document.documentElement.classList.add("gsap-ready");
  gsap.defaults({ duration: 0.72, ease: "power3.out" });

  const heroTimeline = gsap.timeline({ defaults: { autoAlpha: 0, y: 24 } });
  heroTimeline
    .from(".github-link", { y: -12, duration: 0.45 })
    .from(".logo-mark", { scale: 0.94, duration: 0.58 }, 0.08)
    .from("h1", {}, 0.16)
    .from(".tag-list span", { y: 14, duration: 0.48, stagger: 0.055 }, 0.32)
    .from(".tagline", { y: 16, duration: 0.54 }, 0.48)
    .from(".download-button", { y: 18, duration: 0.54, stagger: 0.08 }, 0.62)
    .from(".hero-media", { x: 34, y: 0, scale: 0.985, duration: 0.86 }, 0.22)
    .from(".wave", { y: 22, duration: 0.82 }, 0.48);

  if (!window.ScrollTrigger) return;

  gsap.registerPlugin(ScrollTrigger);
  gsap.set([".feature-row", ".info-card"], { autoAlpha: 0, y: 38 });

  ScrollTrigger.batch(".feature-row, .info-card", {
    start: "top 82%",
    once: true,
    interval: 0.08,
    batchMax: 3,
    onEnter: (elements) => {
      gsap.to(elements, {
        autoAlpha: 1,
        y: 0,
        duration: 0.72,
        ease: "power3.out",
        stagger: 0.12,
        overwrite: "auto",
      });
    },
  });

  gsap.from(".site-footer", {
    autoAlpha: 0,
    y: 18,
    duration: 0.46,
    ease: "power2.out",
    scrollTrigger: {
      trigger: ".site-footer",
      start: "top bottom",
      once: true,
    },
  });
};

const loadUpdateJson = async () => {
  try {
    const response = await fetch("update.json");
    if (!response.ok) throw new Error("HTTP 状态错误");
    const data = await response.json();

    // 1. 更新顶部和直链下载按钮的版本信息和直链
    const apkDownloadBtn = document.querySelector(
      ".primary-button.download-button",
    );
    if (apkDownloadBtn) {
      const apkUrl =
        data.apkUrl ||
        (data.versionName
          ? `assets/DCTimer-BLE-v${data.versionName}.apk`
          : "");
      if (apkUrl) {
        apkDownloadBtn.setAttribute("href", apkUrl);
      }
    }

    const apkBtnSub = document.querySelector(
      ".primary-button.download-button .apk-version",
    );
    if (apkBtnSub && data.versionName) {
      apkBtnSub.textContent = `v${data.versionName}`;
    }

    // 2. 更新最新更新卡片的版本徽章
    const versionBadge = document.querySelector(".version-badge");
    if (versionBadge && data.versionName) {
      versionBadge.textContent = `v${data.versionName}`;
    }

    // 3. 从 update.json notes 或对应版本 changelog 动态生成更新日志列表
    const changelogContent = document.getElementById("changelog-content");
    if (changelogContent) {
      const notesLines = await loadUpdateNotes(data);
      if (notesLines.length > 0) {
        changelogContent.innerHTML = "";
        notesLines.forEach((note) => {
          const li = document.createElement("li");
          li.textContent = note;
          changelogContent.appendChild(li);
        });
      }
    }
  } catch (error) {
    console.warn("无法加载 update.json，已使用预置的默认静态版本内容:", error);
  }
};

// 执行动画和数据动态加载
initGsapAnimations();
loadUpdateJson();
loadFullChangelog();

document.querySelectorAll(".image-zoom-trigger").forEach((button) => {
  button.addEventListener("click", () => {
    if (!dialog || !dialogImage) return;

    dialogImage.src = button.dataset.full || "";
    dialogImage.alt = button.dataset.alt || "";
    resetZoom();
    dialog.showModal();
  });
});

closeButton?.addEventListener("click", () => {
  dialog?.close();
});

changelogTrigger?.addEventListener("click", () => {
  lockPageScroll();
  changelogDialog?.showModal();
});

changelogCloseButton?.addEventListener("click", () => {
  changelogDialog?.close();
});

changelogDialog?.addEventListener("click", (event) => {
  if (event.target === changelogDialog) {
    changelogDialog.close();
  }
});

changelogDialog?.addEventListener("close", unlockPageScroll);

dialog?.addEventListener("click", (event) => {
  if (event.target === dialog) {
    dialog.close();
  }
});

dialogImage?.addEventListener("pointerdown", (event) => {
  zoomState.pointers.set(event.pointerId, event);
  dialogImage.setPointerCapture(event.pointerId);

  const pointers = getPointerPair();
  if (pointers.length === 1) {
    zoomState.startX = pointers[0].clientX - zoomState.x;
    zoomState.startY = pointers[0].clientY - zoomState.y;
  }

  if (pointers.length === 2) {
    zoomState.startDistance = getDistance(pointers[0], pointers[1]);
    zoomState.startScale = zoomState.scale;
    const midpoint = getMidpoint(pointers[0], pointers[1]);
    zoomState.startMidX = midpoint.x - zoomState.x;
    zoomState.startMidY = midpoint.y - zoomState.y;
  }
});

dialogImage?.addEventListener("pointermove", (event) => {
  if (!zoomState.pointers.has(event.pointerId)) return;
  zoomState.pointers.set(event.pointerId, event);

  const pointers = getPointerPair();
  if (pointers.length === 2) {
    const distance = getDistance(pointers[0], pointers[1]);
    const nextScale = clamp(
      (distance / zoomState.startDistance) * zoomState.startScale,
      1,
      4,
    );
    const midpoint = getMidpoint(pointers[0], pointers[1]);
    zoomState.scale = nextScale;
    zoomState.x = midpoint.x - zoomState.startMidX;
    zoomState.y = midpoint.y - zoomState.startMidY;
    applyTransform();
    return;
  }

  if (pointers.length === 1 && zoomState.scale > 1) {
    zoomState.x = pointers[0].clientX - zoomState.startX;
    zoomState.y = pointers[0].clientY - zoomState.startY;
    applyTransform();
  }
});

const endPointer = (event) => {
  zoomState.pointers.delete(event.pointerId);

  if (zoomState.scale <= 1.02) {
    zoomState.scale = 1;
    zoomState.x = 0;
    zoomState.y = 0;
    applyTransform();
  }

  const pointers = getPointerPair();
  if (pointers.length === 1) {
    zoomState.startX = pointers[0].clientX - zoomState.x;
    zoomState.startY = pointers[0].clientY - zoomState.y;
  }
};

dialogImage?.addEventListener("pointerup", endPointer);
dialogImage?.addEventListener("pointercancel", endPointer);
dialogImage?.addEventListener("lostpointercapture", endPointer);

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && dialog?.open) {
    dialog.close();
  }

  if (event.key === "Escape" && changelogDialog?.open) {
    changelogDialog.close();
  }
});

dialog?.addEventListener("close", resetZoom);
