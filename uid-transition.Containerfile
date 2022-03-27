FROM registry.gitlab.com/signald/signald:0.17.0-69-50e60294-amd64
USER root
ADD uid-transition-entrypoint.sh /bin/uid-transition-entrypoint.sh
ENTRYPOINT ["/bin/uid-transition-entrypoint.sh"]