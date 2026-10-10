(() => {
  "use strict";
  const config = window.COREMC_CONFIG || {};
  const address = typeof config.joinAddress === "string" ? config.joinAddress.trim() : "";
  const discord = typeof config.discordUrl === "string" ? config.discordUrl.trim() : "";
  const addressNode = document.getElementById("server-address");
  const copyButton = document.getElementById("copy-address");
  const status = document.getElementById("copy-status");
  const discordLink = document.getElementById("discord-link");
  if (address && /^[a-zA-Z0-9.-]+(?::[0-9]{1,5})?$/.test(address)) {
    addressNode.textContent = address;
    copyButton.disabled = false;
    copyButton.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(address);
        status.textContent = "Address copied. See you in the sky!";
      } catch (_) {
        status.textContent = "Copy this address manually: " + address;
      }
    });
  }
  if (/^https:\/\/discord\.(gg|com)\//i.test(discord)) {
    discordLink.href = discord;
    discordLink.textContent = "Join our Discord ↗";
    discordLink.classList.remove("disabled");
    discordLink.removeAttribute("aria-disabled");
    discordLink.removeAttribute("tabindex");
    discordLink.target = "_blank";
    discordLink.rel = "noopener noreferrer";
  }
})();
