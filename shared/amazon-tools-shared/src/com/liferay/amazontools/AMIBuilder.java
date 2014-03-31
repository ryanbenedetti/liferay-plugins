/**
 * Copyright (c) 2000-2013 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.amazontools;

import com.amazonaws.services.ec2.model.AvailabilityZone;
import com.amazonaws.services.ec2.model.CreateImageRequest;
import com.amazonaws.services.ec2.model.CreateImageResult;
import com.amazonaws.services.ec2.model.DeregisterImageRequest;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.DescribeImagesResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.InstanceStateChange;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import com.amazonaws.util.json.JSONObject;

import jargs.gnu.CmdLineParser;

import java.io.ByteArrayOutputStream;
import java.io.File;

import java.security.Security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile;
import net.schmizz.sshj.xfer.FileSystemFile;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * @author Ivica Cardic
 */
public class AMIBuilder extends BaseAMIBuilder {

	public static void main(String[] args) throws Exception {
		CmdLineParser cmdLineParser = new CmdLineParser();

		CmdLineParser.Option baseDirOption = cmdLineParser.addStringOption(
			"base.dir");
		CmdLineParser.Option propertiesFileNameOption =
			cmdLineParser.addStringOption("properties.file.name");
		CmdLineParser.Option imageNameOption = cmdLineParser.addStringOption(
			"image.name");

		cmdLineParser.parse(args);

		AMIBuilder amiBuilder = new AMIBuilder(
			(String)cmdLineParser.getOptionValue(baseDirOption),
			(String)cmdLineParser.getOptionValue(propertiesFileNameOption),
			(String)cmdLineParser.getOptionValue(imageNameOption));

		try {
			amiBuilder.start();
			amiBuilder.runProvisioners();
			amiBuilder.createImage();
			amiBuilder.destroy();
		}
		catch (Exception e) {
			e.printStackTrace();

			amiBuilder.destroy();
		}
	}

	public AMIBuilder(
			String baseDirName, String propertiesFileName, String imageName)
		throws Exception {

		super(propertiesFileName);

		_baseDirName = baseDirName;
		_imageName = imageName;

		Security.addProvider(new BouncyCastleProvider());

		_provisioners = getProvisioners(properties);
	}

	protected void createImage() {
		CreateImageRequest createImageRequest = new CreateImageRequest();

		createImageRequest.setInstanceId(_instanceId);
		createImageRequest.setName(_imageName);

		CreateImageResult createImageResult = amazonEC2Client.createImage(
			createImageRequest);

		System.out.println("Creating image for instance " + _instanceId);

		boolean created = false;

		for (int i = 0; i < 6; i ++) {
			sleep(30);

			created = isImageCreated(createImageResult.getImageId());

			if (created) {
				System.out.println(
					"Created image " + createImageResult.getImageId());

				break;
			}
		}

		if (!created) {
			System.out.println(
				"Unable to create image " + createImageResult.getImageId());

			deregisterImage(createImageResult.getImageId());
		}
	}

	protected void deregisterImage(String imageId) {
		DeregisterImageRequest deregisterImageRequest =
			new DeregisterImageRequest();

		deregisterImageRequest.setImageId(imageId);

		amazonEC2Client.deregisterImage(deregisterImageRequest);
	}

	protected void destroy() {
		terminateInstance(_instanceId);

		amazonEC2Client.shutdown();
	}

	protected void executeSessionCommand(SSHClient sshClient, String command)
		throws Exception {

		Session session = sshClient.startSession();

		try {
			Session.Command sessionCommand = session.exec(command);

			ByteArrayOutputStream byteArrayOutputStream = IOUtils.readFully(
				sessionCommand.getInputStream());

			System.out.println("Result: " + byteArrayOutputStream.toString());

			sessionCommand.join(5, TimeUnit.SECONDS);
		}
		finally {
			session.close();
		}
	}

	protected String getKeyFileName() {
		StringBuilder sb = new StringBuilder(6);

		sb.append(System.getProperty("user.home"));
		sb.append(File.separator);
		sb.append(".ssh");
		sb.append(File.separator);
		sb.append(properties.getProperty("key.name"));
		sb.append(".pem");

		return sb.toString();
	}

	protected Map<String, String> getProvisioners(Properties properties) {
		Map<String, String> provisioners = new TreeMap<String, String>();

		Set<String> names = properties.stringPropertyNames();

		for (String name : names) {
			if (name.contains("provisioners")) {
				String value = properties.getProperty(name);

				provisioners.put(name, value);
			}
		}

		return provisioners;
	}

	protected Instance getRunningInstance(String instanceId) {
		DescribeInstancesRequest describeInstancesRequest =
			new DescribeInstancesRequest();

		List<Filter> filters = new ArrayList<Filter>();

		Filter filter = new Filter();

		filter.setName("instance-state-name");

		List<String> values = new ArrayList<String>();

		values.add("running");

		filter.setValues(values);

		filters.add(filter);

		describeInstancesRequest.setFilters(filters);

		List<String> instanceIds = new ArrayList<String>();

		instanceIds.add(instanceId);

		describeInstancesRequest.setInstanceIds(instanceIds);

		DescribeInstancesResult describeInstancesResult =
			amazonEC2Client.describeInstances(describeInstancesRequest);

		List<Reservation> reservations =
			describeInstancesResult.getReservations();

		if (reservations.isEmpty()) {
			return null;
		}

		Reservation reservation = reservations.get(0);

		List<Instance> instances = reservation.getInstances();

		return instances.get(0);
	}

	protected boolean isImageCreated(String imageId) {
		DescribeImagesRequest describeImagesRequest =
			new DescribeImagesRequest();

		List<Filter> filters = new ArrayList<Filter>();

		Filter filter = new Filter();

		filter.setName("state");

		List<String> values = new ArrayList<String>();

		values.add("available");

		filter.setValues(values);

		filters.add(filter);

		describeImagesRequest.setFilters(filters);

		List<String> imageIds = new ArrayList<String>();

		imageIds.add(imageId);

		describeImagesRequest.setImageIds(imageIds);

		DescribeImagesResult describeImagesResult =
			amazonEC2Client.describeImages(describeImagesRequest);

		List<Image> images = describeImagesResult.getImages();

		return !images.isEmpty();
	}

	protected boolean isZoneAvailable(String zoneName) {
		DescribeAvailabilityZonesResult describeAvailabilityZonesResult =
			amazonEC2Client.describeAvailabilityZones();

		List<AvailabilityZone> availabilityZones =
			describeAvailabilityZonesResult.getAvailabilityZones();

		for (AvailabilityZone availabilityZone : availabilityZones) {
			if (zoneName.equals(availabilityZone.getZoneName())) {
				return true;
			}
		}

		return false;
	}

	protected void runProvisioners() throws Exception {
		sleep(45);

		SSHClient sshClient = new SSHClient();

		sshClient.addHostKeyVerifier(new PromiscuousVerifier());

		sshClient.setTimeout(
			Integer.parseInt(properties.getProperty("ssh.timeout")) * 1000);

		sshClient.useCompression();

		System.out.println("Connecting via SSH to " + _publicIpAddress);

		sshClient.connect(_publicIpAddress);

		PKCS8KeyFile keyFile = new PKCS8KeyFile();

		keyFile.init(new File(getKeyFileName()));

		sshClient.authPublickey(
				properties.getProperty("ssh.username"), keyFile);

		try {
			Set<String> keys = _provisioners.keySet();

			for (String key : keys) {
				String provisioner = _provisioners.get(key);

				if (key.contains("shell.inline")) {
					runShellInlineProvisioner(sshClient, provisioner);
				}
				else if (key.contains("shell.script")) {
					String shellScriptFilePath = null;

					if (provisioner.startsWith(File.separator)) {
						shellScriptFilePath = provisioner;
					}
					else {
						shellScriptFilePath =
							_baseDirName + File.separator + provisioner;
					}

					runShellScriptProvisioner(sshClient, shellScriptFilePath);
				}
				else {
					JSONObject jsonObject = new JSONObject(provisioner);

					String filePath = null;

					String src = jsonObject.getString("src");

					if (src.startsWith(File.separator)) {
						filePath = src;
					}
					else {
						filePath =
							_baseDirName + File.separator +
								jsonObject.getString("src");
					}

					uploadFile(
						sshClient, filePath, jsonObject.getString("dest"));
				}
			}
		}
		finally {
			sshClient.disconnect();
		}
	}

	protected void runShellInlineProvisioner(
			SSHClient sshClient, String command)
		throws Exception {

		System.out.println("Executing shell inline: " + command);

		executeSessionCommand(sshClient, command);
	}

	protected void runShellScriptProvisioner(
			SSHClient sshClient, String shellScriptFilePath)
		throws Exception {

		uploadFile(sshClient, shellScriptFilePath, "/tmp");

		System.out.println("Executing shell script: " + shellScriptFilePath);

		File shellScriptFile = new File(shellScriptFilePath);

		String uploadFilePath = "/tmp/" + shellScriptFile.getName();

		String command = "chmod +x " + uploadFilePath + "; " + uploadFilePath;

		executeSessionCommand(sshClient, command);

		command = "rm " + uploadFilePath;

		System.out.println("Deleting shell script: " + shellScriptFilePath);

		executeSessionCommand(sshClient, command);
	}

	protected void start() {
		RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

		String availabilityZone = properties.getProperty("availability.zone");

		if (!isZoneAvailable(availabilityZone)) {
			throw new RuntimeException(
				"Zone " + availabilityZone + " is not available.");
		}

		String imageId = getImageId(properties.getProperty("image.name"));

		runInstancesRequest.setImageId(imageId);
		runInstancesRequest.setInstanceType(
			properties.getProperty("instance.type"));
		runInstancesRequest.setKeyName(properties.getProperty("key.name"));
		runInstancesRequest.setMaxCount(1);
		runInstancesRequest.setMinCount(1);

		Placement placement = new Placement();

		placement.setAvailabilityZone(availabilityZone);

		runInstancesRequest.setPlacement(placement);

		List<String> securityGroupsIds = new ArrayList<String>();

		securityGroupsIds.add(properties.getProperty("security.group.id"));

		runInstancesRequest.setSecurityGroupIds(securityGroupsIds);

		RunInstancesResult runInstancesResult = amazonEC2Client.runInstances(
			runInstancesRequest);

		Reservation reservation = runInstancesResult.getReservation();

		List<Instance> instances = reservation.getInstances();

		if (!instances.isEmpty()) {
			Instance instance = instances.get(0);

			_instanceId = instance.getInstanceId();
			_publicIpAddress = instance.getPublicIpAddress();

			StringBuilder sb = new StringBuilder(12);

			sb.append("Instance ");
			sb.append(" ImageId: ");
			sb.append(instance.getImageId());
			sb.append(" InstanceId: ");
			sb.append(_instanceId);
			sb.append(" InstanceType: ");
			sb.append(instance.getInstanceType());
			sb.append(" KeyName: ");
			sb.append(instance.getKeyName());
			sb.append(" State: ");

			InstanceState instanceState = instance.getState();

			sb.append(instanceState.getName());
			sb.append(" is starting.");

			System.out.println(sb.toString());
		}
		else {
			throw new RuntimeException("Instance creation has failed.");
		}

		String reservationId = reservation.getReservationId();

		System.out.println(
			"Reservation Id of the executed transaction: " + reservationId);

		boolean isInstanceRunning = false;

		for (int i = 0; i < 6; i++) {
			sleep(30);

			Instance instance = getRunningInstance(_instanceId);

			if (instance != null) {
				_publicIpAddress = instance.getPublicIpAddress();

				isInstanceRunning = true;

				StringBuilder sb = new StringBuilder(8);

				sb.append("Instance InstanceId: ");
				sb.append(_instanceId);
				sb.append(" Public IP ");
				sb.append("address: ");
				sb.append(_publicIpAddress);
				sb.append(" State: ");

				InstanceState instanceState = instance.getState();

				sb.append(instanceState.getName());
				sb.append(" has been started.");

				System.out.println(sb.toString());

				break;
			}
		}

		if (!isInstanceRunning) {
			throw new RuntimeException(
				"Instance InstanceId: " + _instanceId +
					" has failed to start.");
		}
	}

	protected void terminateInstance(String instanceId) {
		TerminateInstancesRequest terminateInstancesRequest =
			new TerminateInstancesRequest();

		List<String> instanceIds = terminateInstancesRequest.getInstanceIds();

		instanceIds.add(instanceId);

		TerminateInstancesResult terminateInstancesResult =
			amazonEC2Client.terminateInstances(terminateInstancesRequest);

		List<InstanceStateChange> instanceStateChanges =
			terminateInstancesResult.getTerminatingInstances();

		if (!instanceStateChanges.isEmpty()) {
			System.out.println("Terminated instance " + instanceId);
		}
		else {
			System.out.println("Unable to terminate instance " + instanceId);
		}
	}

	protected void uploadFile(
			SSHClient sshClient, String fileName, String destinationDir)
		throws Exception {

		System.out.println("Uploading file " + fileName);

		SFTPClient sftpClient = sshClient.newSFTPClient();

		try {
			sftpClient.put(new FileSystemFile(fileName), destinationDir);
		}
		finally {
			sftpClient.close();
		}
	}

	private String _baseDirName;
	private String _imageName;
	private String _instanceId;
	private Map<String, String> _provisioners;
	private String _publicIpAddress;

}