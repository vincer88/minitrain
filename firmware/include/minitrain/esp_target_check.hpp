#pragma once

#if defined(ESP_PLATFORM)
#ifndef CONFIG_IDF_TARGET_ESP32
#error "Le firmware Minitrain est prévu pour la cible ESP32. Exécutez 'idf.py set-target esp32'."
#endif
#endif
