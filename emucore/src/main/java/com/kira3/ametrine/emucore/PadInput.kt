package com.kira3.ametrine.emucore

/** A SNES pad button. [code] is the core's index for it (SnesController::Buttons). */
enum class PadButton(internal val code: Int) {
    Up(8),
    Down(9),
    Left(10),
    Right(11),
    A(0),
    B(1),
    X(2),
    Y(3),
    L(4),
    R(5),
    Start(7),
    Select(6),
}

/** An analog stick. The SNES pad has none; a physical controller's left stick works as the D-pad. */
enum class PadStick {
    Left,
    Right,
}
