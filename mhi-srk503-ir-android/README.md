# MHI SRK503 IR

Experimental Android infrared remote for Mitsubishi Heavy Industries SRK503HENF-W.

Protocol basis supplied by the user from reverse engineering of a very similar MHI remote:
- 36 kHz carrier
- 64 bits = 32 data bits + inverted copy
- header 6000 / 7500 us
- each bit mark 500 us
- 0 gap 1500 us, 1 gap 3500 us
- special footer 500 / 7500 / 500 us

The app uses Android ConsumerIrManager and requires a phone with a built-in IR emitter.
The included conservative power-off test frame is intended to verify whether this protocol is accepted by the target SRK503HENF-W before relying on the generated frames.
