export JBOSS_CONFIG=standalone

echo "About to prepare Restcomm server location $RESTCOMM_HOME"

echo "VOICERSS: $VOICERSS"
echo "LOCAL_INTERFACE: $LOCAL_INTERFACE"
echo "LOG_LEVEL: $LOG_LEVEL"

FILE=$RESTCOMM_HOME/bin/restcomm/restcomm.conf
#MS_FILE=$RESTCOMM_HOME/bin/restcomm/mediaserver.conf

# VoiceRSS config
if [ -n "$VOICERSS" ]; then
  #Add the VOICERSS key to restcomm.conf
  echo "Updating Restcomm conf for VoiceRSS key"
  sed -e "s|VOICERSS_KEY='.*'|VOICERSS_KEY='$VOICERSS'|" $FILE > $FILE.bak
  mv $FILE.bak $FILE
else
  sed -e "s/VOICERSS_KEY='.*'|VOICERSS_KEY=''|" $FILE > $FILE.bak
  mv $FILE.bak $FILE
fi

# Ethernet Address config
if [ -n "LOCAL_INTERFACE" ]; then
	sed -e "s/NET_INTERFACE=.*/NET_INTERFACE='$LOCAL_INTERFACE'/" $FILE > $FILE.bak
	mv $FILE.bak $FILE
else
	sed -e "s/NET_INTERFACE=.*/NET_INTERFACE='eth0'/" $FILE > $FILE.bak
	mv $FILE.bak $FILE
fi

# Configure MGCP Timeout MGCP_RESPONSE_TIMEOUT
sed -e "s/MGCP_RESPONSE_TIMEOUT=.*/MGCP_RESPONSE_TIMEOUT=1000/" $FILE > $FILE.bak
mv $FILE.bak $FILE

# Configure log level
sed -e "s/LOG_LEVEL=.*/LOG_LEVEL=$LOG_LEVEL/" $FILE > $FILE.bak
mv $FILE.bak $FILE

sed -e "s/AKKA_LOG_LEVEL=.*/AKKA_LOG_LEVEL=$LOG_LEVEL/" $FILE > $FILE.bak
mv $FILE.bak $FILE

sed -e "s/LOG_LEVEL_COMPONENT_GOVNIST=.*/LOG_LEVEL_COMPONENT_GOVNIST=$LOG_LEVEL/" $FILE > $FILE.bak
mv $FILE.bak $FILE

sed -e "s/LOG_LEVEL_COMPONENT_SIPSERVLET=.*/LOG_LEVEL_COMPONENT_SIPSERVLET=$LOG_LEVEL/" $FILE > $FILE.bak
mv $FILE.bak $FILE

sed -e "s/LOG_LEVEL_COMPONENT_SIPRESTCOMM=.*/LOG_LEVEL_COMPONENT_SIPRESTCOMM=$LOG_LEVEL/" $FILE > $FILE.bak
mv $FILE.bak $FILE

sed -e "s/LOG_LEVEL_COMPONENT_RESTCOMM=.*/LOG_LEVEL_COMPONENT_RESTCOMM=$LOG_LEVEL/" $FILE > $FILE.bak
mv $FILE.bak $FILE

FILE=$RESTCOMM_HOME/bin/standalone.conf
sed -e "s/$JAVA_OPTS -Djboss.server.default.config=standalone-sip.xml/$JAVA_OPTS -Djboss.server.default.config=standalone-sip.xml -Djboss.boot.thread.stack.size=1/" $FILE > $FILE.bak
mv $FILE.bak $FILE
