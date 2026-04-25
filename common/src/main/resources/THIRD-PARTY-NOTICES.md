# Renderium Third-Party Notices

This project incorporates third-party software components. Below are the copyright notices and license texts for each component.

---

## NVIDIA Streamline SDK

**Version:** 2.10.3  
**License:** MIT License  
**Source:** https://github.com/NVIDIAGameWorks/Streamline

### License Text

```
Copyright (c) 2024 NVIDIA Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Included DLL Files

The following Streamline SDK DLL files are included unmodified:

- `sl.interposer.dll` - Core Streamline interposer
- `sl.common.dll` - Common functionality
- `sl.dlss.dll` - DLSS Super Resolution
- `sl.dlss_g.dll` - DLSS Frame Generation (optional)
- `sl.reflex.dll` - NVIDIA Reflex (optional)
- `sl.xess.dll` - Intel XeSS (optional)
- `sl.fsr.dll` - AMD FSR (optional)

---

## NVIDIA DLSS SDK

**License:** NVIDIA RTX SDKs License  
**Source:** https://github.com/NVIDIAGameWorks/Streamline

### License Terms

The DLSS and DLSS Frame Generation components are governed by the NVIDIA RTX SDKs License, which includes the following key terms:

1. **Redistribution:** The SDK may be redistributed in object code form as part of your application, provided your application has substantial additional functionality.

2. **Restrictions:**
   - You may not reverse engineer, decompile, or disassemble the SDK
   - You may not redistribute the SDK as a stand-alone product
   - You must protect NVIDIA's intellectual property rights
   - You must notify NVIDIA of any known unauthorized use

3. **No Endorsement:** You may not claim that your application is sponsored or endorsed by NVIDIA without a separate agreement.

---

## Summary

| Component | License | Redistribution |
|-----------|---------|----------------|
| Streamline Core | MIT | ✅ Allowed (unmodified, with license) |
| DLSS Plugin | NVIDIA RTX SDKs License | ✅ Allowed (as part of app with substantial functionality) |
| DLSS-G Plugin | NVIDIA RTX SDKs License | ✅ Allowed (as part of app with substantial functionality) |
| Reflex Plugin | NVIDIA RTX SDKs License | ✅ Allowed (as part of app with substantial functionality) |
| Sodium (Optional) | LGPL-3.0 | ✅ Allowed (dynamic linking via Mixin, not a derivative work) |

---

## Sodium (Optional Dependency)

**Version:** Optional runtime dependency  
**License:** GNU Lesser General Public License v3.0 (LGPL-3.0-only)  
**Author:** JellySquid  
**Source:** https://github.com/CaffeineMC/sodium-fabric

### Relationship with Renderium

Renderium has an **optional dependency** on Sodium with two operational modes:

1. **Full Performance Mode (No Sodium)**: Renderium operates independently with zero contact with Sodium. LGPL does not apply to this mode.

2. **Compatibility Mode (With Sodium)**: When Sodium is installed, Renderium extends Sodium's settings interface via Mixin injection. This is legally equivalent to **dynamic linking**, which is explicitly permitted under LGPL-3.0 Section 4 without infecting the non-LGPL program.

### Legal Position

- **Not a Derivative Work**: Renderium does not include any Sodium source code. All code is independently written, even when implementing similar functionality. Under copyright law, independently written code constitutes an independent work, regardless of functional similarity.

- **Dynamic Linking via Mixin**: In Java/Minecraft modding, Mixin bytecode injection at runtime is legally interpreted as "dynamic linking". LGPL-3.0 Section 4 explicitly allows non-LGPL programs to combine with LGPL libraries via dynamic linking, without requiring the non-LGPL program to be open-sourced.

- **No Source Code Inclusion**: Renderium's build configuration uses `modCompileOnly` (not `implementation` or `embed`) for Sodium dependencies, ensuring no Sodium `.class` files are included in the distributed JAR.

### License Compliance

This mod optionally interacts with Sodium. Sodium is licensed under LGPL-3.0-only by JellySquid. For details, visit: https://github.com/CaffeineMC/sodium-fabric

In compliance with LGPL-3.0:

- Clear attribution of Sodium as an optional dependency
- Project link and license information included in this notice
- No Sodium source code or compiled classes included in Renderium
- Dynamic linking pattern via Mixin follows LGPL-3.0 Section 4 guidelines

### License Text

```
GNU LESSER GENERAL PUBLIC LICENSE
Version 3, 29 June 2007

Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
Everyone is permitted to copy and distribute verbatim copies
of this license document, but changing it is not allowed.

This version of the GNU Lesser General Public License incorporates
the terms and conditions of version 3 of the GNU General Public License,
supplemented by the additional permissions listed below.
```

For the complete LGPL-3.0 license text, see: https://www.gnu.org/licenses/lgpl-3.0.html

---

## Renderium Compliance Statement

Renderium complies with all license requirements by:

1. **Unmodified Distribution:** All DLL files are distributed in their original, unmodified form as provided by NVIDIA.

2. **License Preservation:** This file contains the complete license text and copyright notices for all third-party components.

3. **Substantial Functionality:** Renderium is a comprehensive rendering optimization mod that provides substantial functionality beyond the SDK components, including:
   - Vulkan backend optimization
   - Frame graph reconstruction
   - Command buffer optimization
   - LOD management
   - Super resolution integration (DLSS/FSR/XeSS)
   - Frame generation support
   - Performance monitoring and configuration

4. **No Reverse Engineering:** Renderium does not reverse engineer, decompile, or disassemble any SDK components.

5. **No Standalone Distribution:** SDK components are only distributed as part of the Renderium mod, not as standalone products.

---

*Last updated: 2026-04-15*
