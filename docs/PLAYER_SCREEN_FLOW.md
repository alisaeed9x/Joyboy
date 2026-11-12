# Player Screen Flow — v939

## Mobile source-aware playback

```mermaid
flowchart TD
    A[Open media] --> B{Origin}
    B -->|YouTube| C[MeeTube YouTube playback]
    B -->|Joyboy Browser| D[MeeTube direct playback + Browser origin context]
    B -->|Local / WhatsApp / shared| E[MeeTube direct playback + external origin context]
    C --> F{Fullscreen / minimize}
    D --> F
    E --> F
    F -->|Expand| G[Same playback session]
    F -->|Browser-origin minimize| H[Android system PiP over Browser]
    F -->|Other supported host| I[MeeTube MiniHost card]
    F -->|No host / deep link| J[Native PiP or safe close policy]
    H --> G
    I --> G
```

## Browser-origin minimize decision (v939)

```mermaid
sequenceDiagram
    participant B as Joyboy Browser
    participant P as MtPlay / MtWeb
    participant M as MobilePlaybackActivity
    participant S as Android PiP
    B->>P: Open stream with ret=browser, page/ref, headers
    P->>P: Store source metadata by media URI
    P->>M: Open MeeTube playback
    M->>P: Ask preferSystemPip for current source
    P-->>M: true for ret=browser
    M->>S: Enter system PiP; skip BrowseView MiniHost fallback
    S-->>B: Browser remains visible underneath
```

## Android TV route

```mermaid
flowchart TD
    A[TV media launch] --> B{mt_tv_player enabled?}
    B -->|No, default| C[Legacy Joyboy TV player]
    B -->|Yes, experimental| D[MeeTube TV player + D-pad layer]
    D --> E[Remote API parity]
    E --> F[Physical TCL validation]
    F -->|Approved| G[Future default decision]
```

The v939 implementation creates the opt-in flag but deliberately does not claim that the experimental route is ready. Legacy TV playback remains the default.
