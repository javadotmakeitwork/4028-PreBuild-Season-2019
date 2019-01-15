package org.usfirst.frc.team4028.robot.subsystems;

//#region  == Define Imports ==
import org.usfirst.frc.team4028.robot.Constants;
import org.usfirst.frc.team4028.robot.RobotMap;
import org.usfirst.frc.team4028.robot.auton.RobotState;
import org.usfirst.frc.team4028.robot.auton.control.Path;
import org.usfirst.frc.team4028.robot.auton.control.PathFollower;
import org.usfirst.frc.team4028.robot.auton.motion.RigidTransform;
import org.usfirst.frc.team4028.robot.auton.motion.Rotation;
import org.usfirst.frc.team4028.robot.auton.motion.Twist;
import org.usfirst.frc.team4028.robot.auton.util.Kinematics;

import com.ctre.phoenix.motorcontrol.ControlMode;
import com.ctre.phoenix.motorcontrol.FeedbackDevice;
import com.ctre.phoenix.motorcontrol.LimitSwitchNormal;
import com.ctre.phoenix.motorcontrol.LimitSwitchSource;
import com.ctre.phoenix.motorcontrol.NeutralMode;
import com.ctre.phoenix.motorcontrol.StatusFrame;
import com.ctre.phoenix.motorcontrol.VelocityMeasPeriod;
import com.ctre.phoenix.motorcontrol.can.TalonSRX;

import edu.wpi.first.wpilibj.DoubleSolenoid;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.command.Subsystem;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;

import org.usfirst.frc.team4028.robot.sensors.NavXGyro;
import org.usfirst.frc.team4028.robot.util.GeneralUtilities;
import org.usfirst.frc.team4028.robot.util.LogDataBE;
//#endregion

/**
 * This class defines the Chassis Subsystem, it is responsible for:
 * 	- Left & Right drive Motors
 *  - Solenoid that controls the shifting
 */
public class Chassis extends Subsystem 
{

	private TalonSRX _leftMaster, _leftSlave, _rightMaster, _rightSlave;
	private DoubleSolenoid _shifter;
	
	private NavXGyro _navX = NavXGyro.getInstance();
	
	public static final double ENCODER_COUNTS_PER_WHEEL_REV = 4539.5; //1097		// account for gear boxes

	public double _leftMtrDriveSetDistanceCmd;
	public double _rightMtrDriveSetDistanceCmd;
	private double _targetAngle, _angleError;
	private boolean _isTurnRight;
	private static final double ENCODER_ROTATIONS_PER_DEGREE = 97.598; //ENCODER_COUNTS_PER_WHEEL_REV/360;//97.598; //
	private RobotState _robotState = RobotState.getInstance();
	private double _leftMasterVelocityLoggingLastLogTime;
	private double _leftMasterVelocityLoggingThisTime;
	private double _leftMasterPreviousVelocity = 0;
	private double _leftMasterCurrentVelocity = 0;
	private boolean _isFirstTimeLoggingAccel = true;

	public enum ChassisState
	{
		UNKNOWN,
		PERCENT_VBUS,
		AUTO_TURN, 
		FOLLOW_PATH,
		DRIVE_SET_DISTANCE
	}
	
	private static final double[] MOTION_MAGIC_TURN_PIDF_GAINS = {1.6, 0.0, 16.0, 0.419};
	private static final double[] MOTION_MAGIC_STRAIGHT_PIDF_GAINS = {4.0, 0.0, 95.0, 0.52};
	private static final double[] LOW_GEAR_VELOCITY_PIDF_GAINS = {0.06, 0.0, 0.6, 0.355}; 
	private static final double[] HIGH_GEAR_VELOCITY_PIDF_GAINS = {0.18, 0.0, 2.7, 0.154}; 
    
	// private static final int[] MOTION_MAGIC_TURN_VEL_ACC = {80 * 150, 170 * 150};
	//private static final int[] MOTION_MAGIC_STRAIGHT_VEL_ACC = {80 * 150, 170 * 150};
	
	ChassisState _chassisState = ChassisState.UNKNOWN;
	Path _currentPath;
	PathFollower _pathFollower;
	double _leftEncoderPrevDistance, _rightEncoderPrevDistance = 0;
	double _centerTargetVelocity, _leftTargetVelocity, _rightTargetVelocity;
	//=====================================================================================
	// Define Singleton Pattern
	//=====================================================================================
	private static Chassis _instance = new Chassis();
	
	public static Chassis getInstance() 
	{
		return _instance;
	}
	
	// private constructor for singleton pattern
	private Chassis() 
	{
		_leftMaster = new TalonSRX(RobotMap.LEFT_DRIVE_MASTER_CAN_ADDR);
		_leftSlave = new TalonSRX(RobotMap.LEFT_DRIVE_SLAVE_CAN_ADDR);
		_rightMaster = new TalonSRX(RobotMap.RIGHT_DRIVE_MASTER_CAN_ADDR);
		_rightSlave = new TalonSRX(RobotMap.RIGHT_DRIVE_SLAVE_CAN_ADDR);
		
		_leftSlave.follow(_leftMaster);
		_rightSlave.follow(_rightMaster);
		
		_leftMaster.setInverted(true);
		_leftSlave.setInverted(true);
		_rightMaster.setInverted(false);
		_rightSlave.setInverted(false);
		
		configMasterMotors(_leftMaster);
		configMasterMotors(_rightMaster);
        
        configDriveMotors(_leftMaster);
        configDriveMotors(_rightMaster);
        configDriveMotors(_leftSlave);
		configDriveMotors(_rightSlave);
		
		_leftMaster.setNeutralMode(NeutralMode.Brake);
		_leftSlave.setNeutralMode(NeutralMode.Brake);
		_rightMaster.setNeutralMode(NeutralMode.Brake);
		_rightSlave.setNeutralMode(NeutralMode.Brake);


		_shifter = new DoubleSolenoid(RobotMap.PCM_CAN_ADDR, RobotMap.SHIFTER_EXTEND_PCM_PORT, RobotMap.SHIFTER_RETRACT_PCM_PORT);
	
	}


	public void updateChassis(double timestamp)
	{
		switch(_chassisState) 
		{
			case UNKNOWN:
				return;
			case PERCENT_VBUS:
				return;
				
			case AUTO_TURN:
				GeneralUtilities.setPIDFGains(_leftMaster, MOTION_MAGIC_TURN_PIDF_GAINS);
				GeneralUtilities.setPIDFGains(_rightMaster, MOTION_MAGIC_TURN_PIDF_GAINS);
				_leftMaster.configMotionCruiseVelocity(1680, 10);
				_leftMaster.configMotionAcceleration(8400, 10);
				_rightMaster.configMotionCruiseVelocity(1680, 10);
				_rightMaster.configMotionAcceleration(8400, 10);
				//moveToTargetAngle();
				setHighGear(false);
				return;
				
			case DRIVE_SET_DISTANCE:
				GeneralUtilities.setPIDFGains(_leftMaster, MOTION_MAGIC_STRAIGHT_PIDF_GAINS);
				GeneralUtilities.setPIDFGains(_rightMaster, MOTION_MAGIC_STRAIGHT_PIDF_GAINS);
				_leftMaster.configMotionCruiseVelocity(2100, 10);
				_leftMaster.configMotionAcceleration(16800, 10);
				_rightMaster.configMotionCruiseVelocity(2100, 10);
				_rightMaster.configMotionAcceleration(16800, 10);
				moveToTargetPosDriveSetDistance();
				return;
				
			case FOLLOW_PATH:
				if (get_isHighGear()) 
				{
					GeneralUtilities.setPIDFGains(_leftMaster, HIGH_GEAR_VELOCITY_PIDF_GAINS);
					GeneralUtilities.setPIDFGains(_rightMaster, HIGH_GEAR_VELOCITY_PIDF_GAINS);
				} 
				else 
				{
					GeneralUtilities.setPIDFGains(_leftMaster, LOW_GEAR_VELOCITY_PIDF_GAINS);
					GeneralUtilities.setPIDFGains(_rightMaster, LOW_GEAR_VELOCITY_PIDF_GAINS);
				}
				
				if (_pathFollower != null) 
					updatePathFollower(timestamp);
				return;
		}
	}



	
	/* ===== Chassis State: PERCENT VBUS ===== */
	/** Arcade drive with throttle and turn inputs. Includes anti-tipping. */
	public synchronized void arcadeDrive(double throttle, double turn) {
		//_chassisState = ChassisState.PERCENT_VBUS;
		
		if(_navX.isPitchPastThreshhold()) 
		{
			setLeftRightCommand(ControlMode.PercentOutput, 0.0, 0.0);
			DriverStation.reportError("Tipping Threshold", false);
		} 
		else if ((Math.abs(get_leftVelocityInchesPerSec() - get_rightVelocityInchesPerSec())) < 5.0) {
			setLeftRightCommand(ControlMode.PercentOutput, throttle + 0.7 * turn, throttle - 0.7 * turn);
		} 
		else 
		{
			setLeftRightCommand(ControlMode.PercentOutput, throttle + 0.5 * turn, throttle - 0.5 * turn);
		} 
	}
	
	/* ===== SHIFTER ===== */
	public synchronized void toggleShifter() 
	{
    	setHighGear(!get_isHighGear());	// Inverse of current solenoid state
    }
	
	public synchronized void setHighGear(boolean isHighGear) 
	{
		if (isHighGear) 
			_shifter.set(Constants.SHIFTER_HIGH_GEAR_POS);
		else 
			_shifter.set(Constants.SHIFTER_LOW_GEAR_POS);
	}
	
	private void configMasterMotors(TalonSRX talon) 
	{
		talon.configSelectedFeedbackSensor(FeedbackDevice.CTRE_MagEncoder_Relative, 0, 0);
		talon.setStatusFramePeriod(StatusFrame.Status_2_Feedback0, 5, 0);
	
        talon.configVelocityMeasurementPeriod(VelocityMeasPeriod.Period_10Ms, 0);
        talon.configVelocityMeasurementWindow(32, 0);
        
        talon.configOpenloopRamp(0.4, 10);
		talon.configClosedloopRamp(0.0, 0);
	}
	
	private void configDriveMotors(TalonSRX talon) 
	{
		talon.configForwardLimitSwitchSource(LimitSwitchSource.Deactivated, LimitSwitchNormal.Disabled, 0);
		talon.configReverseLimitSwitchSource(LimitSwitchSource.Deactivated, LimitSwitchNormal.Disabled, 0);
        
        talon.enableCurrentLimit(false);
        
        talon.configPeakOutputForward(1.0, 10);
        talon.configPeakOutputReverse(-1.0, 10);
        talon.configNominalOutputForward(0, 10);
        talon.configNominalOutputReverse(0, 10);
        talon.configContinuousCurrentLimit(Constants.BIG_NUMBER, 10);
	}
	
    // Put methods for controlling this subsystem
    // here. Call these from Commands.

	public void initDefaultCommand() {}
	
	public void setMotionMagicCmdInches(double Distance)
	{
		_chassisState=ChassisState.DRIVE_SET_DISTANCE;
		_leftMtrDriveSetDistanceCmd = _leftMaster.getSelectedSensorPosition(0)+ InchestoNU(Distance);
		_rightMtrDriveSetDistanceCmd = _rightMaster.getSelectedSensorPosition(0)+InchestoNU(Distance);

		System.out.println("Target Position: " + _leftMtrDriveSetDistanceCmd);
		System.out.println("Current Position: " + _leftMaster.getSelectedSensorPosition(0));
		setHighGear(true);
	}

	public void moveToTargetPosDriveSetDistance ()
	{
		setLeftRightCommand(ControlMode.MotionMagic, _leftMtrDriveSetDistanceCmd, _rightMtrDriveSetDistanceCmd);
	}
	
	public void stop()
	{
		_chassisState=ChassisState.PERCENT_VBUS;
		setLeftRightCommand(ControlMode.PercentOutput, 0, 0);
		setHighGear(true);

	}

	//==================================================================================
	//AUTOTURN
	//==================================================================================

	public synchronized void setTargetAngleAndTurnDirection(double targetAngle, boolean isTurnRight) 
	{
		_targetAngle = targetAngle;
		_isTurnRight = isTurnRight;
		setHighGear(false);
		_chassisState = ChassisState.AUTO_TURN;
	}

	public void moveToTargetAngle() 
	{
		// TODO: This code needs to be simplified. Should convert angles to vectors and use dot product to get angle difference.
		if((!_isTurnRight && get_Heading() > _targetAngle) || (_isTurnRight && get_Heading() < _targetAngle))
		{
			_angleError = _targetAngle - get_Heading();
		} 			
		else if(!_isTurnRight && get_Heading() < _targetAngle)
		{
			_angleError = _targetAngle - get_Heading() - 360;
		}
		else if(_isTurnRight && get_Heading() > _targetAngle)
		{
			_angleError = 360 - get_Heading() + _targetAngle;
		}

		double encoderError = ENCODER_ROTATIONS_PER_DEGREE * _angleError;		
		double leftDriveTargetPos = get_leftPos() + encoderError;
		double rightDriveTargetPos = get_rightPos() - encoderError;
		SmartDashboard.putNumber("Yaw", get_Heading());
		SmartDashboard.putNumber("Error", rightDriveTargetPos/ENCODER_COUNTS_PER_WHEEL_REV);
		
		setLeftRightCommand(ControlMode.MotionMagic, leftDriveTargetPos, rightDriveTargetPos);
	}
	//====================================================================================
	// PATH FOLLOWING
	//===================================================================================

	public void zeroEncoders()
	{
		_leftMaster.getSensorCollection().setQuadraturePosition(0, 10);
		_rightMaster.getSensorCollection().setQuadraturePosition(0, 10);
	}

	public void zeroGyro()
	{
		_navX.zeroYaw();
	}

	public void zeroSensors()
	{
		zeroEncoders();
		zeroGyro();
	}



	public synchronized void setWantDrivePath(Path path, boolean reversed) 
	{
		if (_currentPath != path || _chassisState != ChassisState.FOLLOW_PATH) 
		{
			_leftEncoderPrevDistance = get_leftPos()/ENCODER_COUNTS_PER_WHEEL_REV * Constants.DRIVE_WHEEL_DIAMETER_IN * Math.PI;
	        _rightEncoderPrevDistance = get_leftPos()/ENCODER_COUNTS_PER_WHEEL_REV * Constants.DRIVE_WHEEL_DIAMETER_IN * Math.PI;
            RobotState.getInstance().resetDistanceDriven();
            _pathFollower = new PathFollower(path, reversed, path.maxAccel, path.maxDecel, path.inertiaSteeringGain);
            _chassisState = ChassisState.FOLLOW_PATH;
            _currentPath = path;
		} 
		else 
		{
        	setLeftRightCommand(ControlMode.Velocity, 0.0, 0.0);
        }
    }

	public void updatePathFollower(double timestamp) 
	{
		estimateRobotState(timestamp);
		RigidTransform _robotPose = RobotState.getInstance().getLatestFieldToVehicle().getValue();
		Twist command = _pathFollower.update(timestamp, _robotPose, RobotState.getInstance().getDistanceDriven(), RobotState.getInstance().getPredictedVelocity().dx);
		if (!_pathFollower.isFinished()) 
		{
			Kinematics.DriveVelocity setpoint = Kinematics.inverseKinematics(command);
			final double maxDesired = Math.max(Math.abs(setpoint.left), Math.abs(setpoint.right));
            final double scale = maxDesired > Constants.DRIVE_VELOCITY_MAX_SETPOINT ? Constants.DRIVE_VELOCITY_MAX_SETPOINT / maxDesired : 1.0;
            setLeftRightCommand(ControlMode.Velocity, inchesPerSecToNU(setpoint.left * scale), inchesPerSecToNU(setpoint.right * scale));
            _centerTargetVelocity = command.dx;
			_leftTargetVelocity = setpoint.left;
			_rightTargetVelocity = setpoint.right;
		} 
		else 
		{
			setLeftRightCommand(ControlMode.Velocity, 0.0, 0.0);
		}
	}
	public synchronized boolean isDoneWithPath() {
		if (_chassisState == ChassisState.FOLLOW_PATH && _pathFollower != null){
			if (_pathFollower.isFinished()){
				System.out.println("Chassis Done With Path");
				return true;
			}
			else{
				return false;
			}
		} else {
           // System.out.println("Robot is not in path following mode");
			return true;
		}
    }

    /** Path following e-stop */
    public synchronized void forceDoneWithPath() {
        if (_chassisState == ChassisState.FOLLOW_PATH && _pathFollower != null)
            _pathFollower.forceFinish();
		else{}
		
           // System.out.println("Robot is not in path following mode");
	}
	public synchronized double getRemainingPathDistance() {
		if (_pathFollower != null) {
			return _pathFollower.remainingPathLength();
		} 
		return 0;
	}








	//=====================================================================================
	// Property Accessors
	//=====================================================================================
	public double get_leftSpeed() {
		return _leftMaster.getSelectedSensorVelocity(0) * (600 / ENCODER_COUNTS_PER_WHEEL_REV);
	}
	
	public double get_rightSpeed() {
		return -_rightMaster.getSelectedSensorVelocity(0) * (600 / ENCODER_COUNTS_PER_WHEEL_REV);
	}

    public double get_leftVelocityInchesPerSec() {
        return rpmToInchesPerSecond(get_leftSpeed());
    }

    public double get_rightVelocityInchesPerSec() {
        return rpmToInchesPerSecond(get_rightSpeed());
	}
	

	public synchronized boolean get_isHighGear() {
		return _shifter.get() == Constants.SHIFTER_HIGH_GEAR_POS;
	}

	public double get_leftPos()
	{
		return _leftMaster.getSelectedSensorPosition(0);
	}
	public double get_rightPos()
	{
		return _rightMaster.getSelectedSensorPosition(0);
	}
	
	public double get_Heading() 
	{
		if(_navX.getYaw()>=0)
		{
			return _navX.getYaw();
		}
		else
		{
			return 360+_navX.getYaw();
		}
	}

	//=====================================================================================
	// Private Helper methods below
	//=====================================================================================
	public void setLeftRightCommand(ControlMode mode, double leftCommand, double rightCommand) {
		_leftMaster.set(mode, leftCommand);
		_rightMaster.set(mode, rightCommand);
	}
	   
    private static double rpmToInchesPerSecond(double rpm) {
        return rotationsToInches(rpm) / 60;
    }
    
    private static double rotationsToInches(double rot) {
        return rot * (Constants.DRIVE_WHEEL_DIAMETER_IN * Math.PI);
    } 

	private static double InchestoNU (double inches){
		return inches * ENCODER_COUNTS_PER_WHEEL_REV/(Constants.DRIVE_WHEEL_DIAMETER_IN * Math.PI);
	}
	private static double NUtoInches (double NU)
	{
		return NU *Constants.DRIVE_WHEEL_DIAMETER_IN*Math.PI / ENCODER_COUNTS_PER_WHEEL_REV;
	}

	public static double inchesPerSecToNU(double inches_per_second) 
	{
        return inches_per_second * ENCODER_COUNTS_PER_WHEEL_REV / (Constants.DRIVE_WHEEL_DIAMETER_IN * Math.PI * 10);
	}

	public static double NUper100msToInchesPerSec(double NU_per_100ms){
		return NU_per_100ms*10*Constants.DRIVE_WHEEL_DIAMETER_IN*Math.PI/(ENCODER_COUNTS_PER_WHEEL_REV);
	}
	public double getLeftSpeedRPM() {
		return _leftMaster.getSelectedSensorVelocity(0) * (600 / ENCODER_COUNTS_PER_WHEEL_REV);
	}
	
	public double getRightSpeedRPM() {
		return -_rightMaster.getSelectedSensorVelocity(0) * (600 / ENCODER_COUNTS_PER_WHEEL_REV);
	}
	public double getLeftVelocityInchesPerSec() {
        return rpmToInchesPerSecond(getLeftSpeedRPM());
    }

    public double getRightVelocityInchesPerSec() {
        return rpmToInchesPerSecond(getRightSpeedRPM());
	}
	
	private double getAcceleration(){
		this._leftMasterVelocityLoggingLastLogTime = this._leftMasterVelocityLoggingThisTime;
		this._leftMasterVelocityLoggingThisTime = Timer.getFPGATimestamp();
		this._leftMasterPreviousVelocity = this._leftMasterCurrentVelocity;
		this._leftMasterCurrentVelocity = NUper100msToInchesPerSec(_leftMaster.getSelectedSensorVelocity(0));
		if (! this._isFirstTimeLoggingAccel){
			double dt = this._leftMasterVelocityLoggingThisTime - this._leftMasterVelocityLoggingLastLogTime;
			double dv = this._leftMasterCurrentVelocity - this._leftMasterPreviousVelocity;
			double accel = dv/dt;
			return accel;
		} else {
			this._isFirstTimeLoggingAccel = false;
			return 0;
		}

	}
	
	private void estimateRobotState( double timestamp)
	{
		final double left_distance = NUtoInches(get_leftPos());
		final double right_distance = NUtoInches(get_rightPos());
		final Rotation gyro_angle = Rotation.fromDegrees(get_Heading());
		final Twist odometry_velocity = _robotState.generateOdometryFromSensors(
				left_distance - _leftEncoderPrevDistance, right_distance - _rightEncoderPrevDistance, gyro_angle);
		final Twist predicted_velocity = Kinematics.forwardKinematics(getLeftVelocityInchesPerSec(),
				getRightVelocityInchesPerSec());
		_robotState.addObservations(timestamp, odometry_velocity, predicted_velocity);
		_leftEncoderPrevDistance = left_distance;
		_rightEncoderPrevDistance = right_distance;
	}
	//=====================================================================================
	// Support Methods
	//=====================================================================================

	public void printVelocityDiagnosticData(){
		// System.out.println("Left Actual Velocity: " + Double.toString(get_leftVelocityInchesPerSec()));
		// System.out.println("Left Target: " + Double.toString( _leftTargetVelocity));
		// System.out.println("Right Actual Velocity [in/s]: " + Double.toString(-get_rightVelocityInchesPerSec()));
		// System.out.println("Right Target Velocity: " + Double.toString(_rightTargetVelocity));

	}
	public void updateLogData(LogDataBE logData) 
	{
		logData.AddData("Left Actual Velocity [in/s]", String.valueOf(GeneralUtilities.roundDouble(get_leftVelocityInchesPerSec(), 2)));
		logData.AddData("Left Target Velocity [in/s]", String.valueOf(GeneralUtilities.roundDouble(_leftTargetVelocity, 2)));
		//logData.AddData("Left Output Current", String.valueOf(GeneralUtilities.roundDouble(_leftMaster.getOutputCurrent(), 2)));
	

		logData.AddData("Right Actual Velocity [in/s]", String.valueOf(GeneralUtilities.roundDouble(-get_rightVelocityInchesPerSec(), 2)));
		logData.AddData("Right Target Velocity [in/s]", String.valueOf(GeneralUtilities.roundDouble(_rightTargetVelocity, 2)));
		//logData.AddData("Right Output Current", String.valueOf(GeneralUtilities.roundDouble(_rightMaster.getOutputCurrent(), 2)));
		//logData.AddData("Chassis Acceleration [in/s/s]", String.valueOf(GeneralUtilities.roundDouble(getAcceleration(), 2)));
	
		//logData.AddData("Pose X", String.valueOf(RobotState.getInstance().getLatestFieldToVehicle().getValue().getTranslation().x()));
		//logData.AddData("Pose Y", String.valueOf(RobotState.getInstance().getLatestFieldToVehicle().getValue().getTranslation().y()));
		//logData.AddData("Pose Angle", String.valueOf(RobotState.getInstance().getLatestFieldToVehicle().getValue().getRotation().getDegrees()));
		//logData.AddData("Remaining Distance", String.valueOf(getRemainingPathDistance()));
		
		//logData.AddData("Center Target Velocity", String.valueOf(GeneralUtilities.roundDouble(_centerTargetVelocity, 2)));

	}


	
	public void updateDashboard() 
	{
		SmartDashboard.putBoolean("IsHighGear", get_isHighGear());
		SmartDashboard.putNumber("Chassis: Left Velocity", GeneralUtilities.roundDouble(get_leftVelocityInchesPerSec(), 2));
		SmartDashboard.putNumber("Chassis: Right Velocity", GeneralUtilities.roundDouble(get_rightVelocityInchesPerSec(), 2));
		
		SmartDashboard.putNumber("Chassis: Left Wheel Target Velocity", -1.0); //GeneralUtilities.roundDouble(_leftTargetVelocity, 2));
		SmartDashboard.putNumber("Chasiss: Right Wheel Target Velocity",  -1.0); // GeneralUtilities.roundDouble(_leftTargetVelocity, 2));
		
		SmartDashboard.putNumber("Chassis: Angle", GeneralUtilities.roundDouble(get_Heading(), 2));
		SmartDashboard.putString("Chassis: Robot Pose", "N/A"); //RobotState.getInstance().getLatestFieldToVehicle().getValue().toString());
	}

}