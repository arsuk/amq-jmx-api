node('jdk8') {
    // define commands
    def mvnHome
    def mvnCmd

    stage('Prepare') {
        git credentialsId: 'eWL-AP-EPP-SPS', branch: 'develop', url: 'https://gitlab.kazan.atosworldline.com/ap-payments-nonfunctional-testing/amq-jmx-api.git'
        mvnHome = tool 'Maven 3.5.x'
        mvnCmd = "${mvnHome}/bin/mvn"
    }
    stage('Package') {
        configFileProvider([configFile(fileId: 'MavenGlobalInternalzoneNexus', variable: 'MAVEN_SETTINGS'), configFile(fileId: 'MavenToolchainsDefault', variable: 'MAVEN_TOOLCHAINS')]) {
            withEnv(['JAVA_HOME=D:/Java/current']) {
	            bat "${mvnCmd} -s $MAVEN_SETTINGS -t $MAVEN_TOOLCHAINS package -DskipTests=true"
	        }
		}
    }
    stage('Verify') {
        configFileProvider([configFile(fileId: 'MavenGlobalInternalzoneNexus', variable: 'MAVEN_SETTINGS'), configFile(fileId: 'MavenToolchainsDefault', variable: 'MAVEN_TOOLCHAINS')]) {
            withEnv(['JAVA_HOME=D:/Java/current']) {
	            bat "${mvnCmd} -s $MAVEN_SETTINGS -t $MAVEN_TOOLCHAINS org.jacoco:jacoco-maven-plugin:prepare-agent verify"
	            bat "${mvnCmd} -s $MAVEN_SETTINGS -t $MAVEN_TOOLCHAINS sonar:sonar -DskipTests=true"
	        }
		}
    }
    stage('Deploy') {
        configFileProvider([configFile(fileId: 'MavenGlobalInternalzoneNexus', variable: 'MAVEN_SETTINGS'), configFile(fileId: 'MavenToolchainsDefault', variable: 'MAVEN_TOOLCHAINS')]) {
            withEnv(['JAVA_HOME=D:/Java/current']) {
	            bat "${mvnCmd} -s $MAVEN_SETTINGS -t $MAVEN_TOOLCHAINS deploy -DskipTests=true"
	        }
		}
    }
    stage('Cleanup after build') {
        configFileProvider([configFile(fileId: 'MavenGlobalInternalzoneNexus', variable: 'MAVEN_SETTINGS'), configFile(fileId: 'MavenToolchainsDefault', variable: 'MAVEN_TOOLCHAINS')]) {
            withEnv(['JAVA_HOME=D:/Java/current']) {
	            bat "${mvnCmd} -s $MAVEN_SETTINGS -t $MAVEN_TOOLCHAINS clean -B"
	        }
		}
    }
}
