This repository is a fork of the original [N0BOY/FT8CN](https://github.com/N0BOY/FT8CN/) with the following improvements:

1. The project now includes the original C++ source files, and you can choose whether to use the original `.so` libraries or build from source.
2. The app now supports exporting and importing QSO logs directly from the application.


FT8CN  
Run FT8 natively on Android  
Check Releases to download the latest APK file.

**Thanks to:**  
Steve Franke (K9AN), Bill Somerville (G4WJS), and Joe Taylor (K1JT) proposed the FT8 and FT4 protocols. The “FT” stands for Franke and Taylor. Their paper, *The FT4 and FT8 Communication Protocols*, explains the design goals of FT4 and FT8 and the implementation details in WSJT-X, which served as the fundamental guide for this app.  
Karlis Goba (YL3JG) provided references for the implementation details in code.
Developed by BG7YOZ and hosted by N0BOY  
BG7YOY, for guidance on basic radio theory during the development of FT8CN, and for designing the app icon.  
BG4IGX, for practical guidance when I first started in amateur radio. You can find many of his teaching videos on Douyin.  
BD7MXN, for testing some radio connection controls and suggesting improvements.  
BH2RSJ, for helping establish an FT8CN test group and providing many valuable suggestions for testing and future improvements.  
BH7ACO, for helping solve the driver and related configuration parameters for a certain radio model.  
BG7IKK, for helping test radios that only support PTT transmission via RTS control.  
BI1NIZ, for helping register an account used for issue collection and FAQ features.  
BD3OOX and the Shijiazhuang Amateur Radio Club, for extracting callsign region mapping data for FT8CN from the JTDX Shijiazhuang edition, enabling callsign location to be mapped down to the provincial level in China.  
VR2UPU (BD7MJO), for guidance on FT8 development and usage experience, and for help with multilingual support.  
BA2BI, for help and guidance on amateur radio fundamentals and log handling for contacts.  
BI3QXJ, for professional guidance on the command set of a certain radio series.  
BG6TQD, for help testing the command set of a certain radio model.  
BG5CSS, for providing a radio model for testing.  
BG7YXN, for providing a radio model for testing.  
BG7YRB, for help with callsign rule calculations.  
BG8KAH, for providing equipment for testing.  
BA7LVG and JE6WUD, for completing the Japanese translation proofreading.  
BG6RI, for helping solve signal report issues in the log.  
SV1EEX, for completing the Greek and Spanish UI translations.  
VR2VRC, for helping correct historical callsign lookup rules.  
BA7NQ, for providing equipment for testing.  
BD7MYM, for guidance in testing a certain radio model.  
NØBOY, for helping provide the GitHub source and translation work.  
BG5JNT, for helping fix recognition of non-standard callsigns.  
BH3NEK, for assisting with testing a certain radio model.  
BG2ALB, for assisting with testing a certain radio model.  
BG6DRU, for assisting with testing a certain radio model.  
BG7NQF, for providing hidden commands for a certain radio model and performing compatibility tests on some devices.  
BH2VSQ, for assisting with testing a certain radio model.  
BG7YBW, for assisting with testing some features.  
BH1RNN, for assisting with testing some features.  
BG7BSM, for helping debug some bugs.  
BH4FTI, for finding and helping debug some bugs.  
BG8BXM (M Ge), for promoting the use of FT8CN; many of his tutorial videos are available on Douyin and Bilibili.  
BA7MFQ, for promoting the use of FT8CN and helping with testing.  
BG2EFX, for providing large-volume logs for testing.  
DS1UFX, for contributing the `(tr)uSDX audio over CAT` code.  
BG8HT, for providing a radio model for testing.  
UB6LUM, for helping solve operating mode settings for a certain radio model.  
BG5VLI, for contributing code to automatically upload logs to Cloudlog and QRZ.

**Disclaimer:**  
FT8CN is intended for research and learning purposes, including decoding and transmitting FT8 signals. The authors are not responsible for any consequences resulting from the user's use of this app.  
Please comply with local laws and regulations when using FT8CN.  
Considering the performance and battery limitations of mobile phones, signal processing uses lightweight computation rather than deep decoding and similar processing.  
If you have suggestions or questions, please submit them in “FAQ / Feedback”.
BG7YOZ  
2022-07-01