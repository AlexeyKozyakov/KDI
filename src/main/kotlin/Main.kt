annotation class CarScope
annotation class EngineScope


@CarScope
@InjectClass
class Car(
    private val wheel: Wheel?,
    door: Lazy<Door>,
    private val engine: Engine
) {
    private val door by door

    init {
        println("Car initialized")
    }

    fun openDoor() {
        door.open()
    }

    fun checkWheel() {
        println(wheel != null)
    }

}

@CarScope
@InjectClass
class Door {
    init {
        println("Door initialized")
    }

    fun open() {
        println("Door opened")
    }
}

class Wheel

@CarScope
@InjectClass
class Engine(
    carDi: DiContainer,
    car: Lazy<Car>
) {

    private val car by car

    private val engineDi = carDi.subDi<EngineScope> {
        bind<EnginePart, Piston>()
        provide { EngineState(running = true) }
    }

    init {
        println("Engine runnung: ${engineDi.get<EngineState>().running}")
    }

    fun chechCarWheel() {
        car.checkWheel()
    }
}

interface EnginePart

@EngineScope
@InjectClass
class Piston(private val candle: Candle) : EnginePart {
    init {
        println("Piston initialized")
    }
}

@EngineScope
class Candle {
    init {
        println("Candle initialized")
    }
}

class EngineState(
    var running: Boolean
)


fun main() {
    val carDi = di<CarScope> { }

    carDi.get<Car>().openDoor()

    carDi.get<Engine>().chechCarWheel()
}
