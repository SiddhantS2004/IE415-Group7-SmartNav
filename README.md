SmartNav: Comparing Dead Reckoning and SLAM-Based Localization on Mobile Devices

SmartNav is an Android application built using Kotlin + Jetpack Compose that compares two localization approaches on mobile devices:

Dead Reckoning (DR) using accelerometer + gyroscope data

SLAM using ARCore’s motion tracking for feature-based localization

The app visualizes both trajectories in real time and demonstrates how IMU-based DR diverges over distance while SLAM remains stable and accurate.

📌 Features
✅ Dead Reckoning (DR)

Uses SensorManager to collect accelerometer, gyroscope, and (optionally) magnetometer data.

Integrates acceleration → velocity → displacement.

Computes orientation using gyroscope + complementary filtering.

Plots DR trajectory live using Jetpack Compose.

Supports step counting to stabilize short-range DR motion.

✅ SLAM (ARCore)

Uses ARCore's motion-tracking subsystem.

Extracts camera pose at each frame.

Projects 3D world coordinates onto a 2D ground-plane trajectory.

Maps obstacles (green/red feature points based on distance).

Significantly more accurate over medium/long distances.

✅ Real-Time Visualization

DR trajectory (red)

SLAM trajectory (blue)

Obstacle point cloud (green / red for < 0.2m)

Zoom controls, reset, start/stop modes.

📲 Demo Video

YouTube link: https://www.youtube.com/watch?v=hwJ3Zmbt1vw
 

CAS_project

🧠 Methodology (Summary)
🔹 Dead Reckoning Pipeline

Raw accelerometer + gyroscope sensing

Low-pass and moving-average filters for noise reduction

Complementary filter for heading

Integrate acceleration twice to estimate position

Render the path in real time

🔹 SLAM Pipeline

ARCore session initialization

Obtain camera pose via getCamera().getPose()

Convert world pose → 2D ground-plane coordinates

Fuse visual features + IMU for drift-free estimation

Map surrounding obstacles based on distance

📈 Performance Analysis
🔸 Why DR Fails Over Long Distances

Sensor noise + bias accumulate during integration

Gyroscope drift corrupts heading

Small errors compound into large displacement deviations

Works well only for short paths (as shown in Figure 1) 

CAS_project

🔸 Why SLAM Performs Better

Uses visual features as reference points

Corrects drift automatically when revisiting features (loop closure)

Fuses camera + IMU for globally consistent tracking

Remains accurate over long paths (Figure 2) 

CAS_project

🖼 Key Results
DR Short Path Result

As shown in Figure 1, DR is reasonably accurate for short movements.


CAS_project

SLAM vs DR in Long Path

SLAM stays close to the true path, while DR drifts dramatically.


CAS_project

Obstacle Mapping

Green dots: features detected
Red dots: close obstacles (<0.2m) (Figure 4)


CAS_project

⚠ Limitations
★ 1. Limited Obstacle Rendering

High obstacle counts (>3000 points) slowed the UI. A cap was added to avoid lag.


CAS_project

★ 2. SLAM–DR Integration Issues

Both systems work independently, but when run together:

SLAM remains stable

DR becomes erratic or stops updating
Likely due to sensor contention and processing load.


CAS_project

★ 3. Phone Orientation Dependency

Accuracy relies on holding the phone upright and forward-facing.
Incorrect pitch/roll degrades both DR and SLAM.


CAS_project

📚 References

Android SensorManager Documentation

ARCore Developer Guides

Jetpack Compose Documentation

Dead Reckoning implementation references

Project Report (IE415) 

CAS_project

👨‍💻 Contributors

Group Name: SmartNav – IE415 Autonomous Systems Project 

CAS_project

Jainil Jagtap (202301032)

Parv Khetawat (202301157)

Siddhant Shekhar (202301268)

Aarya Javia (202201420)
