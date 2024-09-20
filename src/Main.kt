import processing.core.PApplet
import processing.core.PImage
import processing.core.PVector
import processing.sound.BrownNoise
import java.awt.Image
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.pow


object Config {
	const val simWidth: Int = 50
	const val maxHeight: Int = 250
	val gravity: PVector = PVector(0f, -0.001f)
	const val friction: Float = 0.999f
	var renderTargetShip: Boolean = false
	var renderZoom: Boolean = false
	var earthPosition: PVector = PVector(200f, 200f)
	const val displayNormal: Int = 0xffffffff.toInt()
	const val accentTint: Int = 0xffff0000.toInt()
	const val debugTint: Int = 0xfff0066f.toInt()
	const val veryFarAway: Float = 100000000f
}


open class PhysicsItem(var position: PVector, var velocity: PVector, var acceleration: PVector) {
	open fun timeStep(dt: Float) {
		velocity.plusAssign(acceleration)
		position.plusAssign(velocity * dt)
		velocity.timesAssign(Config.friction.pow(dt))
		acceleration.timesAssign(0f)
	}
}

open class PhysicsValue(var position: Float, var velocity: Float, var acceleration: Float) {
	open fun timeStep(dt: Float) {
		velocity += acceleration
		position += velocity * dt
		velocity *= Config.friction.pow(dt)
		acceleration *= 0f
	}
}


class Ship(position: PVector, velocity: PVector, acceleration: PVector, val size: PVector, val rotation: PhysicsValue) :
	PhysicsItem(position, velocity, acceleration) {
	override fun timeStep(dt: Float) {
		acceleration.plusAssign(Config.gravity)
		super.timeStep(dt)
		rotation.timeStep(dt)
	}

	fun rotateLeft(dt: Float) {
		rotation.acceleration = 0.0001f * dt
	}

	fun rotateRight(dt: Float) {
		rotation.acceleration = -0.0001f * dt
	}

	fun thrustUp(dt: Float) {
		acceleration.plusAssign(PVector(0f, 0.002f).rotate(rotation.position) * dt)
	}

}

enum class State {
	FLYING,
	LANDED,
	CRASHED
}

class App : PApplet() {
	var heightMap = mutableListOf<Int>()
	var heightMapDerivative = listOf<Int>()
	var stars = mutableListOf<PVector>()
	var shootingStars = mutableListOf<PhysicsItem>()
	var earthResource: PImage? = null
	lateinit var ship: Ship
	var lastFrameTime = 0L
	var noise: BrownNoise? = null

	val pressedKeys: MutableSet<Int> = mutableSetOf()
	lateinit var state: State

	fun help() {
		println(
			"""
     _____
  __/__|__\___
 |__  O O  __|
    \     /
     \___/
  ~ Lunar Lander ~
---------------------
  Controls:
  
  [←] or [→]  : Turn Left / Right
  [Space]     : Fire Main Thruster
  [R]         : Reset Game
  [V]         : Change View
  [Z]         : Zoom In (Ship View)

---------------------
  On-Screen Info:
  
  - Top Left: Velocity, Position, and Rotation of the ship.
  - Success Criteria: Three lines of text below the stats.
    - If any line is red upon landing, you fail.
  - Landing Zones: Flashing red and green rectangles indicate
    where you can land safely.
  
  Objective: Safely land on the moon's surface! Good luck!
---------------------
		""".trimIndent()
		)

	}

	override fun settings() {
		size(800, 600)
		earthResource = getImage("earth.png")
		help()
	}

	override fun setup() {
		state = State.FLYING
		stars = mutableListOf()
		shootingStars = mutableListOf()
		ship = Ship(
			PVector(random(100f) - 50f, Config.maxHeight * 2f),
			PVector(random(1f) - 0.5f, random(1f) - 0.5f),
			PVector(0f, 0f),
			PVector(10f, 10f),
			PhysicsValue(0.5f, 0f, 0f)
		)
		initMap()
		computeDeltaTime()
		noise?.stop()
		try {
			noise = BrownNoise(this)
		} catch (_: NoClassDefFoundError) {
			println("Audio not supported :c")
		}
	}

	override fun draw() {
		background(0)
		pushMatrix()
		if (Config.renderTargetShip) {
			translate(width.toFloat() / 2, height.toFloat() / 2)
			if (Config.renderZoom) {
				scale(1f / (ship.velocity.mag() + 0.05f))
			} else {
				scale(4f)
			}
			translate(-ship.position.x, ship.position.y)
			translate(-50f * ship.velocity.x, 50 * ship.velocity.y)
		} else {
			translate(width.toFloat() / 2, height.toFloat() - 10)
		}
		scale(1f, -1f)
		when (state) {
			State.FLYING -> {
				testIntersect()
				val dt = computeDeltaTime()
				handleKeys(dt)
				timeStep(dt)
				if (random(10f) < 0.1) spawnShootingStar()
			}

			else -> {}
		}
		ship()
		stars()
		landscape()
		earth()
		popMatrix()
		renderUi()
	}

	fun spawnShootingStar() {
		val width1 = width
		val startX = floatArrayOf(-width1.toFloat(), width1.toFloat()).random()
		val pos = PVector(startX, random(height.toFloat()), random(100f)) + ship.position
		shootingStars.add(PhysicsItem(pos, PVector((2f + random(4f)) * -startX / width1, random(1f)), PVector(0f, 0f)))
	}

	fun getImage(url: String): PImage {
		var image: Image
		try {
			image = ImageIO.read(App::class.java.getResourceAsStream(url))
		} catch (_: IllegalArgumentException) {
			image = ImageIO.read(App::class.java.getResourceAsStream("resources/$url"))
		}
		@Suppress("DEPRECATION")
		return PImage(image)
	}

	fun translate(v: PVector) {
		translate(v.x, v.y)
	}

	private fun initMap() {
		heightMap = (-Config.simWidth / 2 until Config.simWidth / 2).map {
			constrain(
				-(it * it / 2) + Config.maxHeight + floor((randomGaussian() - 0.5f) * 10),
				0,
				Config.maxHeight
			)
		}.toMutableList()
		// Ensure map is solvable
		val guaranteedLandable = (1 until Config.simWidth - 1).random()
		heightMap[guaranteedLandable - 1] = heightMap[guaranteedLandable]
		heightMap[guaranteedLandable + 1] = heightMap[guaranteedLandable]
		heightMapDerivative = heightMap.zipWithNext { a, b -> b - a }
		repeat(1000) { i ->
			val pos = PVector(random(width.toFloat()) - width / 2, random(height.toFloat()), random(100f))
			try {
				if (pos.y > heightMap[landscapeXToIdx(pos.x)] + 10) stars.add(pos)
			} catch (_: IndexOutOfBoundsException) {
				stars.add(pos)
			}
		}
		Config.earthPosition = PVector(width * 0.25f, height * 0.75f)
	}

	fun strokeNormal() = stroke(Config.displayNormal)
	fun fillNormal() = fill(Config.displayNormal)
	fun strokeAccent() = stroke(Config.accentTint)
	fun fillAccent() = fill(Config.accentTint)
	fun strokeDebug() = stroke(Config.debugTint)
	fun fillDebug() = fill(Config.debugTint)

	fun ship() {
		pushMatrix()
		noFill()
		strokeNormal()
		translate(ship.position)
		rotate(ship.rotation.position)
		translate(ship.size * -0.5f)
		strokeNormal()
		ellipse(ship.size.x / 2, ship.size.y * 1.5f, 5f, 5f)
		rect(0f, 0f, ship.size.x, ship.size.y)
		if (' '.code in pressedKeys) {
			fillAccent()
			noStroke()
			triangle(
				0f, 0f,
				ship.size.x * 0.5f, -ship.size.y * (2 - sin(millis() / 100f)),
				ship.size.x, 0f
			)
		}
		popMatrix()
	}

	fun landscapeIdxToX(idx: Int): Float = 8 * (idx - Config.simWidth.toFloat() / 2)

	fun landscapeXToIdx(x: Float): Int = round((x / 8) + (Config.simWidth / 2))

	fun landscape() {
		fill(0)
		beginShape()
		strokeNormal()
		for (i in 0 until Config.simWidth) {
			vertex(landscapeIdxToX(i), heightMap[i].toFloat())
		}
		endShape(CLOSE)
		line(-Config.veryFarAway, 0f, Config.veryFarAway, 0f)
		noStroke()
		// Flash the terrain red if it's not landable
		for (i in -width / 2 until width / 2) {
			val alpha = constrain(-log(millis() % 6000f / 3000) * 50, 0f, 25f)
			infoColor(isLandable(i.toFloat()), alpha)
			rect(i.toFloat(), 0f, 1f, height.toFloat())
		}
	}

	fun timeStep(dt: Float) {
		if (dt > 10000) return  // Too strange
		ship.timeStep(dt)
		shootingStars.forEach { it.timeStep(dt) }
	}

	fun computeDeltaTime(): Float {
		return (-lastFrameTime + frameRateLastNanos.also { lastFrameTime = it }) / 1e7f
	}

	fun debug() {
		strokeDebug()
		fillDebug()
		text(ship.position.str(), 10f, 10f)
		text(ship.velocity.str(), 10f, 30f)
		text(formatFloat(ship.rotation.position), 10f, 50f)
		infoColor(isTerrainOkForLanding())
		text("Terrain: " + isTerrainOkForLanding().toString(), 10f, 90f)
		infoColor(isRotationOkForLanding())
		text("Rotation: " + isRotationOkForLanding().toString(), 10f, 110f)
		infoColor(isVelocityOkForLanding())
		text("Velocity: " + isVelocityOkForLanding().toString(), 10f, 130f)


	}

	private fun infoColor(bool: Boolean, alpha: Float = 255f) =
		if (bool) fill(0f, 255f, 0f, alpha)
		else fill(255f, 0f, 0f, alpha)

	override fun keyPressed() {
		pressedKeys.add(keyCode)
		if (keyCode == 'V'.code) Config.renderTargetShip = !Config.renderTargetShip
		if (keyCode == 'Z'.code) Config.renderZoom = !Config.renderZoom
		if (keyCode == 'R'.code) setup()
	}

	override fun keyReleased() = Unit.also { pressedKeys.remove(keyCode) }

	fun segmentsIntersect(a: PVector, b: PVector, c: PVector, d: PVector): Boolean {
		val a1 = b.y - a.y
		val b1 = a.x - b.x
		val c1 = a1 * a.x + b1 * a.y
		val a2 = d.y - c.y
		val b2 = c.x - d.x
		val c2 = a2 * c.x + b2 * c.y
		val det = a1 * b2 - a2 * b1
		if (det == 0f) return false
		val x = (b2 * c1 - b1 * c2) / det
		val y = (a1 * c2 - a2 * c1) / det
		val isOnAB = (min(a.x, b.x) <= x && x <= max(a.x, b.x)) && (min(a.y, b.y) <= y && y <= max(a.y, b.y))
		val isOnCD = (min(c.x, d.x) <= x && x <= max(c.x, d.x)) && (min(c.y, d.y) <= y && y <= max(c.y, d.y))
		return isOnAB && isOnCD
	}

	fun testIntersect() {
		// Test if the ship intersects the landscape, considering the ship as a rectangle with rotation where the center of rotation is the center of the ship
		val shipPos = ship.position
		val shipSize = ship.size
		val shipRotation = ship.rotation.position
		val shipVertices = listOf(
			PVector(-shipSize.x / 2, -shipSize.y / 2),
			PVector(shipSize.x / 2, -shipSize.y / 2),
			PVector(shipSize.x / 2, shipSize.y / 2),
			PVector(-shipSize.x / 2, shipSize.y / 2),
			PVector(-shipSize.x / 2, -shipSize.y / 2),
		).map { it.rotate(shipRotation) + shipPos }
		val shipEdges = shipVertices.zipWithNext { a, b -> Pair(a, b) }
		val landscapeEdges = (0 until Config.simWidth - 1).map {
			Pair(
				PVector(landscapeIdxToX(it), heightMap[it].toFloat()),
				PVector(landscapeIdxToX(it + 1), heightMap[it + 1].toFloat())
			)
		}.toMutableList()
		landscapeEdges.addAll(
			listOf(
				Pair(
					PVector(-Config.veryFarAway, 0f),
					PVector(Config.veryFarAway, 0f)
				),
			)
		)
		val intersect = shipEdges.any { shipEdge ->
			landscapeEdges.any { landscapeEdge ->
				segmentsIntersect(shipEdge.first, shipEdge.second, landscapeEdge.first, landscapeEdge.second)
			}
		}
		if (intersect) calculateIfCrash()
	}

	fun calculateIfCrash() {
		// We are intersecting the terrain, if our velocity is too high or we are landing on uneven terrain, or we are not facing up, we crash
		val velocityOk = isVelocityOkForLanding()
		val landingOk = isRotationOkForLanding()
		val landingOnEvenTerrain = isTerrainOkForLanding()
		if (!velocityOk || !landingOk || !landingOnEvenTerrain) {
			if (state == State.FLYING) {
				noise?.stop()
				noise = null
				state = State.CRASHED
				println("Crashed because velocityOk: $velocityOk, landingOk: $landingOk, landingOnEvenTerrain: $landingOnEvenTerrain")
			}
			return
		}

		if (state == State.FLYING) {
			noise?.stop()
			noise = null
			state = State.LANDED
			println("Landed!")
		}
	}

	private fun isTerrainOkForLanding(): Boolean =
		(floor(ship.position.x - ship.size.x / 2)..floor(ship.position.x + ship.size.x / 2)).all {
			isLandable(it.toFloat())
		}

	private fun isLandable(x: Float): Boolean = try {
		abs(heightMapDerivative[landscapeXToIdx(x)]) < 2
	} catch (_: IndexOutOfBoundsException) {
		false
	}

	private fun isRotationOkForLanding(): Boolean = abs(ship.rotation.position % (2 * PI)) <= 0.1f

	private fun isVelocityOkForLanding(): Boolean = ship.velocity.mag() < 0.05f

	fun renderUi() {
		debug()
		when (state) {
			State.FLYING -> {
				testIntersect()
				val dt = computeDeltaTime()
				handleKeys(dt)
				timeStep(dt)
			}

			State.LANDED -> {
				pushStyle()
				fill(0f, 255f, 0f)
				textSize(32f)
				textAlign(CENTER)
				text("LANDED", width / 2f, height / 2f)
				popStyle()
			}

			State.CRASHED -> {
				pushStyle()
				fill(255f, 0f, 0f)
				textSize(32f)
				textAlign(CENTER)
				text("CRASHED", width / 2f, height / 2f)
				popStyle()
			}
		}

	}

	fun earth() = image(earthResource, Config.earthPosition.x, Config.earthPosition.y, 50f, 50f)

	fun stars() {
		fillNormal()
		noStroke()
		stars.forEach {
			pushMatrix()
			if (Config.renderTargetShip) {
				translate(it)
				translate(-ship.position * 0.001f * it.z)
				translate(-50f * ship.velocity.x * 0.01f, 50 * ship.velocity.y * 0.01f)
			} else {
				translate(it)
			}
			ellipse(0f, 0f, it.z / 50f, it.z / 50f)
			popMatrix()
		}
		shootingStars.forEach {
			fillNormal()
			noStroke()
			for (i in 0 until 20) {
				fill(255f, 255f - i * 25)
				pushMatrix()
				if (Config.renderTargetShip) {
					translate(it.position)
					translate(-ship.position * 0.001f * it.position.z)
					translate(-50f * ship.velocity.x * 0.01f, 50 * ship.velocity.y * 0.01f)
				} else {
					translate(it.position)
				}
				val sz = pow((10f - i.toFloat()) / 10f, 2f) * it.position.z / 40f
				ellipse(it.velocity.x * -i * 4, it.velocity.y * -i * 4, sz, sz)
				popMatrix()
			}
		}
	}

	private fun handleKeys(dt: Float) {
		if (LEFT in pressedKeys) ship.rotateLeft(dt)
		if (RIGHT in pressedKeys) ship.rotateRight(dt)
		if (' '.code in pressedKeys) {
			ship.thrustUp(dt)
			noise?.play(0.2f)
		} else {
			noise?.play(0.05f)
		}
	}
}

private fun PVector.str(): String {
	return "${formatFloat(x)}, ${formatFloat(y)}"
}

private fun formatFloat(x: Float): String = String.format(Locale.ROOT, "%.2f", x)

operator fun PVector.plusAssign(other: PVector) {
	this.add(other)
}

operator fun PVector.plus(other: PVector): PVector {
	return this.copy().add(other)
}

operator fun PVector.minus(other: PVector): PVector {
	return this.copy().sub(other)
}

operator fun PVector.timesAssign(other: Float) {
	this.mult(other)
}

operator fun PVector.times(other: Float): PVector {
	return this.copy().mult(other)
}

operator fun PVector.unaryMinus(): PVector {
	return this.copy() * -1f
}

fun main() {
	PApplet.main(App::class.java)
}
